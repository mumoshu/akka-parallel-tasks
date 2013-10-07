package com.github.mumoshu.akka.examples.io.echo

import akka.actor._
import akka.testkit._
import org.specs2.mutable.SpecificationLike
import java.net.InetSocketAddress
import akka.util.ByteString

class SimpleEchoClient(port: Int, listener: ActorRef) extends Actor {

  import akka.io._

  import Tcp._
  import context.system

  val remote = new InetSocketAddress("localhost", port)

  IO(Tcp) ! Connect(remote)

  def receive = {
    case CommandFailed(_: Connect) =>
      listener ! "failed"
    case c @ Connected(remote, local) =>
      val connection = sender
      connection ! Register(self)
      context become {
        case str: String => connection ! Write(ByteString(str, "UTF-8"))
        case Received(data) => listener ! new String(data.toArray[Byte], "UTF-8")
        case _: ConnectionClosed => context stop self
      }
  }
}

class SimpleEchoSpec
  extends TestKit(ActorSystem()) with ImplicitSender
  with SpecificationLike {

  sequential

  def doAfter = {
    TestKit.shutdownActorSystem(system)
  }

  "Plain repository" >> {
    val port = 12345
    val echoServer = system.actorOf(Props(
      new SimpleEchoServer(port)))
    val echoClient = system.actorOf(Props(
      new SimpleEchoClient(port, testActor)))

    "scan" in {
      echoClient ! "Hello!"
      expectMsg("Hello!")
      success
    }
  }

}
