package com.github.mumoshu.akka.examples.io.echo

import akka.actor._
import akka.io._
import java.net.InetSocketAddress
import akka.util.ByteString
import akka.io.IO
import java.nio.ByteOrder
import scala.util.Success

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
  def commandHandler: ActorRef
  def eventHandler: ActorRef
  val injector = PipelineFactory.buildWithSinkFunctions(ctx, stages)(
    commandHandler ! _,
    eventHandler ! _
  )
}

class PipelineEchoServer(port: Int) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", port))

  def receive = {
    case b @ Bound(localAddress) =>
    case CommandFailed(_: Bind) => context stop self
    case c @ Connected(remote, local) =>
      val connection = sender
      val handler = context.actorOf(Props(new PipelineEchoHandler(connection) with ActorLogging))
      connection ! Register(handler)
  }

}

class PipelineEchoHandler(connection: ActorRef) extends Actor with EchoInjector {

  val echoServer = context.actorOf(Props(new EchoServer(self) with ActorLogging))

  val eventHandler = echoServer
  val commandHandler = self

  import Tcp._

  def receive = {
    // * Above  -- Cmd --> Below
    case m: Echo =>
      injector.injectCommand(m)
    // Above  -- Cmd --> * Below
    case Success(data: ByteString) =>
      connection ! Write(data)
    // Above <-- Evt --  Below *
    case Received(data) =>
      injector.injectEvent(data)
    case PeerClosed =>
      context stop self
  }
}

case class Reply(m: Echo)

class EchoServer(destination: ActorRef) extends Actor {
  def receive = {
    case Success(m: Echo) =>
      destination ! m
  }
}
