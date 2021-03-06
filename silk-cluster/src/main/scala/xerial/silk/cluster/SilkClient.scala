/*
 * Copyright 2012 Taro L. Saito
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//--------------------------------------
//
// SilkClient.scala
// Since: 2012/12/13 4:50 PM
//
//--------------------------------------

package xerial.silk.cluster

import akka.actor._
import xerial.core.log.Logger
import xerial.core.io.IOUtil
import akka.pattern.ask
import scala.concurrent.Await
import akka.util.Timeout
import scala.concurrent.duration._
import xerial.core.util.{JavaProcess, Shell}
import java.net.URL
import java.io._
import java.util.concurrent.TimeoutException
import xerial.silk.util.ThreadUtil.ThreadManager
import xerial.silk.cluster.framework.{ZooKeeperService, ClusterNodeManager, SilkClientService, ActorService}
import xerial.silk.framework._
import java.util.UUID
import xerial.silk.io.ServiceGuard
import xerial.silk.cluster._


/**
 * SilkClient is a network interface that accepts command from the other hosts
 */
object SilkClient extends Logger {


  private[silk] var client : Option[SilkClient] = None
  val dataTable = collection.mutable.Map[String, AnyRef]()


  case class Env(clientRef:SilkClientRef, zk:ZooKeeperClient)




  case class SilkClientRef(system: ActorSystem, actor: ActorRef) {
    def !(message: Any) = actor ! message
    def ?(message: Any, timeout: Timeout = 3.seconds) = {
      val future = actor.ask(message)(timeout)
      Await.result(future, timeout.duration)
    }
    def terminate {
      this ! Terminate
    }
    def close {
      system.shutdown
    }
    def addr = actor.path
  }

  def localClient = remoteClient(localhost)

  def remoteClient(host: Host, clientPort: Int = config.silkClientPort): ServiceGuard[SilkClientRef] = {
    val system = ActorService.getActorSystem(port = IOUtil.randomPort)
    val akkaAddr = s"${ActorService.AKKA_PROTOCOL}://silk@${host.address}:${clientPort}/user/SilkClient"
    trace(s"Remote SilkClient actor address: $akkaAddr")
    val actor = system.actorFor(akkaAddr)
    new ServiceGuard[SilkClientRef] {
      protected val service = new SilkClientRef(system, actor)
      def close {
        service.close
      }
    }
  }

  private def withLocalClient[U](f: ActorRef => U): U = withRemoteClient(localhost.address)(f)

  private def withRemoteClient[U](host: String, clientPort: Int = config.silkClientPort)(f: ActorRef => U): U = {
    val system = ActorService.getActorSystem(port = IOUtil.randomPort)
    try {
      val akkaAddr = s"${ActorService.AKKA_PROTOCOL}://silk@%s:%s/user/SilkClient".format(host, clientPort)
      debug(s"Remote SilkClient actor address: $akkaAddr")
      val actor = system.actorFor(akkaAddr)
      f(actor)
    }
    finally {
      system.shutdown
    }
  }

  sealed trait ClientCommand
  case object Terminate extends ClientCommand
  case object ReportStatus extends ClientCommand

  case object GetPort
  case class Run(cbid: UUID, closure: Array[Byte])

  case class DownloadDataFrom(host:Host, port:Int, filePath:File, offset:Long, size:Long)
  case class RegisterFile(file:File)
  case class DataReference(id: String, host: Host, port: Int)
  case class RegisterData(args: DataReference)
  case class GetDataInfo(id: String)
  case class ExecuteFunction0[A](function: Function0[A])
  case class ExecuteFunction1[A, B](function: Function1[A, B], argsID: String, resultID: String)
  case class ExecuteFunction2[A, B, C](function: Function2[A, B, C], argsID: String, resultID: String)
  case class ExecuteFunction3[A, B, C, D](function: Function3[A, B, C, D], argsID: String, resultID: String)


  case object OK
}

import SilkClient._
import SilkMaster._

/**
 * SilkClient run the jobs
 *
 * @author Taro L. Saito
 */
class SilkClient(val host: Host, val zk: ZooKeeperClient, val leaderSelector: SilkMasterSelector, val dataServer: DataServer)
  extends Actor
  with SilkClientService
{
  //type LocalClient = SilkClient
  def localClient = this
  def address = host.address

  var master: ActorRef = null
  private val timeout = 10.seconds

  private def serializeObject[A](obj: A): Array[Byte] =
  {
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(obj)
    oos.close
    baos.toByteArray
  }

  override def preStart() = {
    info(s"Start SilkClient at ${host.address}:${config.silkClientPort}")

    startup

    SilkClient.client = Some(this)

    val mr = MachineResource.thisMachine
    val currentNode = Node(host.name, host.address,
      Shell.getProcessIDOfCurrentJVM,
      config.silkClientPort,
      config.dataServerPort,
      config.webUIPort,
      NodeResource(host.name, mr.numCPUs, mr.memory))
    nodeManager.addNode(currentNode)


    // Get an ActorRef of the SilkMaster
    try {
      val masterAddr = s"${ActorService.AKKA_PROTOCOL}://silk@${leaderSelector.leaderID}/user/SilkMaster"
      trace(s"Remote SilkMaster address: $masterAddr, host:$host")

      // wait until the master is ready
      val maxRetry = 10
      var retry = 0
      var masterIsReady = false
      while(!masterIsReady && retry < maxRetry) {
        try {
          master = context.actorFor(masterAddr)
          val ret = master.ask(SilkClient.ReportStatus)(timeout)
          Await.result(ret, timeout)
          masterIsReady = true
        }
        catch {
          case e:TimeoutException =>
            retry += 1
        }
      }
      if(!masterIsReady) {
        error("Failed to find SilkMaster")
        terminate
      }
    }
    catch {
      case e: Exception =>
        error(e)
        terminate
    }

    trace("SilkClient has started")
  }

  override def postRestart(reason: Throwable) {
    info(s"Restart the SilkClient at ${host.prefix}")
    super.postRestart(reason)
  }


  def onLostZooKeeperConnection {
    terminateServices
  }

  def terminateServices {
    context.system.shutdown()
  }

  def terminate {
    terminateServices
    nodeManager.removeNode(host.name)
  }


  override def postStop() {
    info("Stopped SilkClient")
    teardown
  }




  def receive = {
    case Terminate => {
      warn("Recieved a termination signal")
      sender ! OK
      terminate
    }
    case tr @ TaskRequestF0(taskID, cbid, serializedTask, locality) =>
      trace(s"Accepted a task f0: ${taskID.prefix}")
      localTaskManager.execute(cbid, tr)
    case tr @ TaskRequestF1(taskID, cbid, serializedTask, locality) =>
      trace(s"Accepted a task f1: ${taskID.prefix}")
      localTaskManager.execute(cbid, tr)
    case SilkClient.ReportStatus => {
      info(s"Recieved status ping from ${sender.path}")
      sender ! OK
    }
    case RegisterFile(file) => {
      // TODO use hash value of data as data ID or UUID
      warn(s"registerByteData data $file")
      dataServer.registerData(file.getName, file)
    }
    case DownloadDataFrom(host, port, fileName, offset, size) => {
      val dataURL = new URL(s"http://${host.address}:${port}/data/${fileName.getName}:${offset}:${size}")
      warn(s"download data from $dataURL")
      IOUtil.readFully(dataURL.openStream()) { result =>
        debug(s"result: ${result.map(e => f"$e%x").mkString(" ")}")
      }
      sender ! OK
      // TODO how to use the obtained result?
    }
    case r@Run(cbid, closure) => {
      info(s"recieved run command at $host: cb:$cbid")
      val cb = if (!dataServer.containsClassBox(cbid.prefix)) {
        val cb = getClassBox(cbid).asInstanceOf[ClassBox]
        dataServer.register(cb)
        cb
      }
      else {
        dataServer.getClassBox(cbid.prefix)
      }
      Remote.run(cb, r)
    }
    case RegisterData(argsInfo) =>
    {
      val future = master.ask(RegisterDataInfo(argsInfo.id, DataAddr(argsInfo.host, argsInfo.port)))(timeout)
      Await.result(future, timeout) match
      {
        case OK => info(s"Registered information of data ${argsInfo.id} to the SilkMaster")
        case e => warn(s"timeout: ${e}")
      }
    }
    case ExecuteFunction0(func) => func()
    case ExecuteFunction1(func, argsID, resID) =>
    {
      val future = master.ask(AskDataHolder(argsID))(timeout)
      Await.result(future, timeout) match
      {
        case DataNotFound(id) => warn(s"Data request ${id} is not found.")
        case DataHolder(id, holder) =>
        {
          val dataURL = new URL(s"http://${holder.host.address}:${holder.port}/data/${id}")
          info(s"Accessing ${dataURL.toString}")
          IOUtil.readFully(dataURL.openStream())
          {
            arguments =>
              val ois = new ObjectInputStream(new ByteArrayInputStream(arguments))
              val args = ois.readObject.asInstanceOf[Product1[Nothing]]
              for (method <- func.getClass.getDeclaredMethods.find(m => m.getName == "apply" && !m.isSynthetic))
              {
                val retType = method.getReturnType
                retType match
                {
                  case t if t == classOf[Unit] => func(args._1)
                  case _ =>
                    val result = func(args._1)
                    val serializedObject = serializeObject(result)
                    dataServer.registerByteData(resID, serializedObject)
                    val dr = new DataReference(resID, host, client.map(_.dataServer.port).get)
                    self ! RegisterData(dr)
                }
              }
          }
        }
      }
    }
    case ExecuteFunction2(func, argsID, resID) =>
    {
      val future = master.ask(AskDataHolder(argsID))(timeout)
      Await.result(future, timeout) match
      {
        case DataNotFound(id) => warn(s"Data request ${id} is not found.")
        case DataHolder(id, holder) =>
        {
          val dataURL = new URL(s"http://${holder.host.address}:${holder.port}/data/${id}")
          info(s"Accessing ${dataURL.toString}")
          IOUtil.readFully(dataURL.openStream())
          {
            arguments =>
              val ois = new ObjectInputStream(new ByteArrayInputStream(arguments))
              val args = ois.readObject().asInstanceOf[Product2[Nothing, Nothing]]
              func(args._1, args._2)
          }
        }
      }
    }
    case ExecuteFunction3(func, argsID, resID) =>
    {
      val future = master.ask(AskDataHolder(argsID))(timeout)
      Await.result(future, timeout) match
      {
        case DataNotFound(id) => warn(s"Argument request ${id} is not found.")
        case DataHolder(id, holder) =>
        {
          val dataURL = new URL(s"http://${holder.host.address}:${holder.port}/data/${id}")
          info(s"Accessing ${dataURL.toString}")
          IOUtil.readFully(dataURL.openStream)
          {
            arguments =>
              val ois = new ObjectInputStream(new ByteArrayInputStream(arguments))
              val args = ois.readObject.asInstanceOf[Product3[Nothing, Nothing, Nothing]]
              for (method <- func.getClass.getDeclaredMethods.find(m => m.getName == "apply" && !m.isSynthetic))
              {
                val retType = method.getReturnType
                retType match
                {
                  case t if t == classOf[Unit] => func(args._1, args._2, args._3)
                  case _ =>
                    val result = func(args._1, args._2, args._3)
                    val serializedObject = serializeObject(result)
                    dataServer.registerByteData(resID, serializedObject)
                    val dr = new DataReference(resID, host, client.map(_.dataServer.port).get)
                    self ! RegisterData(dr)
                }
              }
          }
        }
      }
    }
    case GetDataInfo(id) =>
    {
      val future = master.ask(AskDataHolder(id))(timeout)
      Await.result(future, timeout) match
      {
        case DataNotFound(id) =>
        {
          warn(s"Data request ${id} is not found.")
          sender ! DataNotFound(id)
        }
        case DataHolder(id, holder) =>
        {
          info(s"Sending data $id info.")
          sender ! DataHolder(id, holder)
        }
      }
    }
    case OK => {
      info(s"Recieved a response OK from: $sender")
    }
    case message => {
      warn(s"unknown message recieved: $message")
    }
  }

}


