package com.github.mumoshu.akka.examples.io.echo

import akka.actor._
import akka.io.Tcp
import java.net.InetSocketAddress
import akka.io.IO

class SimpleEchoServer(port: Int) extends Actor {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", port))

  def receive = {
    case b @ Bound(localAddress) =>
    case CommandFailed(_: Bind) => context stop self
    case c @ Connected(remote, local) =>
      val handler = context.actorOf(Props[SimpleEchoHandler])
      val connection = sender
      connection ! Register(handler)
  }

}

class SimpleEchoHandler extends Actor {

  import Tcp._

  def receive = {
    case Received(data) =>
      sender ! Write(data)
    case PeerClosed =>
      context stop self
  }
}
