package com.github.mumoshu.akka.examples.io.echo

import akka.actor._
import akka.io._
import java.net.InetSocketAddress
import akka.util.ByteString
import akka.io.IO
import java.nio.ByteOrder

// We cannot make this a Value class because it is unusable in event pipeline
case class Echo(data: String)

trait HasByteOrder extends PipelineContext {
  def byteOrder: java.nio.ByteOrder
}

class EchoStage
  extends SymmetricPipelineStage[HasByteOrder, Echo, ByteString] {
  override  def apply(ctx: HasByteOrder) =
    new SymmetricPipePair[Echo, ByteString] {
      implicit val byteOrder = ctx.byteOrder

      // Encoder side
      def commandPipeline = { msg: Echo =>
        val bb = ByteString.newBuilder
        bb.putBytes(msg.data.getBytes("UTF-8"))
        ctx.singleCommand(bb.result)
      }

      // Decoder side
      def eventPipeline = { bs: ByteString =>
        ctx.singleEvent(Echo(new String(bs.toArray, "UTF-8")))
      }
    }
}

trait EchoProtocol {
  val ctx = new HasByteOrder {
    def byteOrder = ByteOrder.BIG_ENDIAN
  }

  val stages = new EchoStage

  val PipelinePorts(cmd, evt, mgmt) =
    PipelineFactory.buildFunctionTriple(ctx, stages)

}

trait EchoInjector extends EchoProtocol {
  val commandHandler: ActorRef
  val eventHandler: ActorRef
  val injector = PipelineFactory.buildWithSinkFunctions(ctx, stages)(
    commandHandler ! _,
    eventHandler ! _
  )
}

class PipelineEchoServer(port: Int) extends Actor {

  import Tcp._
  import context.system

  val echoServer = context.actorOf(Props(new EchoServer))

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", port))

  def receive = {
    case b @ Bound(localAddress) =>
    case CommandFailed(_: Bind) => context stop self
    case c @ Connected(remote, local) =>
      val handler = context.actorOf(Props(new PipelineEchoHandler(echoServer, self)))
      val connection = sender
      connection ! Register(handler)
  }

}

class PipelineEchoHandler(echoServer: ActorRef, echoActor: ActorRef) extends Actor with EchoInjector {

  val eventHandler = echoActor
  val commandHandler = echoServer

  import Tcp._

  def receive = {
    case Received(data) =>
      injector.injectEvent(data)
    case m: Echo =>
      injector.injectCommand(m)
    case PeerClosed =>
      context stop self
  }
}

class EchoServer extends Actor {
  def receive = {
    case m: Echo =>
      sender ! m
  }
}
