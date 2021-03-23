package zhttp.service.server

import zhttp.core.{JChannelHandlerContext, JSimpleChannelInboundHandler, JWebSocketFrame}
import zhttp.service.{ChannelFuture, UnsafeChannelExecutor}
import zhttp.socket.WebSocketFrame
import zio.stream.ZStream

/**
 * Creates a new websocket handler
 */
final case class ServerSocketHandler[R, E](
  zExec: UnsafeChannelExecutor[R],
  socket: WebSocketFrame => ZStream[R, E, WebSocketFrame],
) extends JSimpleChannelInboundHandler[JWebSocketFrame] {

  /**
   * Unsafe channel reader for WSFrame
   */

  override def channelRead0(ctx: JChannelHandlerContext, msg: JWebSocketFrame): Unit = {
    WebSocketFrame.fromJFrame(msg) match {
      case Some(frame) =>
        zExec.unsafeExecute_(ctx) {
          socket(frame)
            .mapM(frame => ChannelFuture.unit(ctx.writeAndFlush(frame.toJWebSocketFrame)))
            .runDrain
        }

      case _ => ()
    }
  }
}
