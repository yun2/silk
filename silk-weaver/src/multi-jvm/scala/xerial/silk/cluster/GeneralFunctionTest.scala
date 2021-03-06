package xerial.silk.cluster

import SilkClient._
import SilkClient.RegisterData
import java.io.{ByteArrayInputStream, ObjectInputStream, ByteArrayOutputStream, ObjectOutputStream}
import java.util.UUID
import SilkMaster.{DataHolder, DataNotFound}
import java.net.URL
import xerial.core.io.IOUtil


object Serializer
{
  def serializeObject(obj: AnyRef): Array[Byte] =
  {
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(obj)
    oos.close
    baos.toByteArray
  }
}

object FunctionGroup
{
  val func0 = () => println("Yes, my master.")
  val func1 = (num: Int) => println(s"Master! I am No.$num")
  val func2 = (num: Int, str: String) => println(s"${str}! I am No.${num}")
  val func3 = (num: Int, str: String, dou: Double) => s"${num}, ${str}, ${dou}"
}

class GeneralFunctionTestMultiJvm1 extends Cluster3Spec
{
  "start cluster and send and execute function" in
    {
      start
      {
        env =>
          val nodeList = xerial.silk.cluster.hosts
          info(s"nodes: ${nodeList.mkString(", ")}")

          // serialize data and get data ID
          val argList = Tuple3(10, "Master", 15.2)
          val serializedArgs = Serializer.serializeObject(argList)
          val argID = UUID.randomUUID.toString

          // registerByteData data to DataServer in the client of this process
          env.client.dataServer.registerByteData(argID, serializedArgs)

          // registerByteData data location to master
          val dr = new DataReference(argID, localhost, SilkClient.client.map(_.dataServer.port).get)
          env.clientActor ! RegisterData(dr)
          val resultIDs = List.fill(nodeList.length)(UUID.randomUUID.toString)
          info("Sending order to clients")
          for ((node, resID) <- nodeList zip resultIDs; client <- SilkClient.remoteClient(node.host, node.clientPort))
          {
            //client ! ExecuteFunction0(FunctionGroup.func0)
            //client ! ExecuteFunction1(FunctionGroup.func1, argID, resID)
            //client ! ExecuteFunction2(FunctionGroup.func2, argID, resID)
            env.clientActor ! ExecuteFunction3(FunctionGroup.func3, argID, resID)
          }

          // sleep while finish other threads
          Thread.sleep(1000)

          // ask answer
          for ((node, resID) <- nodeList zip resultIDs; client <- SilkClient.remoteClient(node.host, node.clientPort))
          {
            def getResult: Array[Byte] =
            {
              env.clientActor ? GetDataInfo(resID) match
              {
                case DataHolder(id, holder) =>
                {
                  val dataURL = new URL(s"http://${holder.host.address}:${holder.port}/data/${id}")
                  info(s"Accessing ${dataURL.toString}")
                  IOUtil.readFully(dataURL.openStream){return _}
                }
                case DataNotFound(id) => getResult
              }
            }

            val ois = new ObjectInputStream(new ByteArrayInputStream(getResult))
            assert("10, Master, 15.2" == ois.readObject)
          }
      }
    }
}

class GeneralFunctionTestMultiJvm2 extends Cluster3Spec
{
  "start cluster and accept data" in
    {
      start(env => {})
    }
}

class GeneralFunctionTestMultiJvm3 extends Cluster3Spec
{
  "start cluster and accept data" in
    {
      start(env => {})
    }
}
