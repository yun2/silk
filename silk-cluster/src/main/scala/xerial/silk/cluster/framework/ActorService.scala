package xerial.silk.cluster.framework

import xerial.silk.framework.{SilkFramework, LifeCycle}
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import xerial.silk.util.ThreadUtil.ThreadManager
import xerial.core.log.Logger
import java.util.concurrent.{TimeUnit, Executors}
import xerial.silk.util.ThreadUtil

object ActorService extends Logger {

  val AKKA_PROTOCOL = "akka"

  private[cluster] def getActorSystem(host: String = "localhost", port: Int) = {
    debug(s"Creating an actor system using $host:$port")
    val akkaConfig = ConfigFactory.parseString(
      """
        |akka.loglevel = "ERROR"
        |akka.daemonic = on
        |akka.event-handlers = ["akka.event.Logging$DefaultLogger"]
        |akka.actor.provider = "akka.remote.RemoteActorRefProvider"
        |akka.remote.transport = "akka.remote.netty.NettyRemoteTransport"
        |akka.remote.netty.connection-timeout = 15s
        |akka.remote.netty.hostname = "%s"
        |akka.remote.netty.port = %d
        |      """.stripMargin.format(host, port))

    //    /
    //    |akka.remote.enabled-transports = ["akka.remote.netty.tcp"]
    //    |akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    //    |akka.remote.netty.tcp.connection-timeout = 15s
    //      |akka.remote.netty.tcp.hostname c= "%s"
    //    |akka.remote.netty.tcp.port = %d

    //|akka.log-config-on-start = on
    //|akka.actor.serialize-messages = on
    //|akka.actor.serialize-creators = on
    //|akka.loggers = ["akka.event.Logging$DefaultLogger"]
    ActorSystem("silk", akkaConfig, Thread.currentThread.getContextClassLoader)
  }

  def apply(address:String, port:Int) = new ActorService {
    val system = ActorService.getActorSystem(address, port)
  }

}

/**
 * @author Taro L. Saito
 */
trait ActorService extends Logger {

  val system : ActorSystem

  def shutdown : Unit = {
    debug(s"shut down the actor system: $system")
    system.shutdown
  }

  private def wrap[R](f: ActorSystem => R) : R = {
    try {
      f(system)
    }
    finally
      shutdown
  }

  def map[B](f: ActorSystem => B) : B = wrap(f)
  def flatMap[B](f:ActorSystem => B) : Option[B] = Some(wrap(f))
  def foreach[U](f:ActorSystem=>U) { wrap(f) }
}