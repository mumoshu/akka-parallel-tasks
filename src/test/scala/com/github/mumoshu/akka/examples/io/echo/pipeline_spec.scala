package com.github.mumoshu.akka.examples.io.echo

import akka.actor._
import akka.testkit._
import org.specs2.mutable.SpecificationLike
import java.net.InetSocketAddress
import akka.util.ByteString

class PipelineEchoSpec
  extends TestKit(ActorSystem()) with ImplicitSender
  with SpecificationLike {

  sequential

  def doAfter = {
    TestKit.shutdownActorSystem(system)
  }

  "Plain repository" >> {
    val port = 12346
    val echoServer = system.actorOf(Props(
      new PipelineEchoServer(port)))
    val echoClient = system.actorOf(Props(
      new SimpleEchoClient(port, testActor)))

    "scan" in {
      echoClient ! "Hello!"
      expectMsg("Hello!")
      echoClient ! "World!"
      expectMsg("World!")
      success
    }
  }

}
