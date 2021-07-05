package sttp.tapir.server.ziohttp

import sttp.capabilities.zio.ZioStreams
import sttp.tapir.{DecodeResult, WebSocketBodyOutput, WebSocketFrameDecodeFailure}
import sttp.ws.{WebSocketClosed, WebSocketFrame}
import zhttp.core.ByteBuf
import zhttp.socket.{Socket, WebSocketFrame => ZioWebSocketFrame}
import zio.stream.ZStream

private[ziohttp] object ZioHttpWebSockets {
  def pipeToBody[REQ, RESP](
      pipe: ZStream[Any, Throwable, REQ] => ZStream[Any, Throwable, RESP],
      o: WebSocketBodyOutput[ZStream[Any, Throwable, REQ] => ZStream[Any, Throwable, RESP], REQ, RESP, _, ZioStreams]
  ): Socket[Any, Nothing, ZioWebSocketFrame, ZioWebSocketFrame] = {
    Socket
      .collect[ZioWebSocketFrame]
      .apply()
      .map(messageToFrame)
      .map(f =>
        o.requests.decode(f) match {
          case failure: DecodeResult.Failure => throw new WebSocketFrameDecodeFailure(f, failure)
          case DecodeResult.Value(v)         => v
        }
      )
      .via(pipe)
      .map(o.responses)
      .takeWhile {
        case WebSocketFrame.Close(_, _) => false
        case _                          => true
      }
      .mapConcat(frameToMessage(_).toList)
  }

  private def messageToFrame(
      m: ZioWebSocketFrame
  ): WebSocketFrame =
    m match {
      case t: ZioWebSocketFrame.Text      => WebSocketFrame.Text(t.text, false, None)
      case ZioWebSocketFrame.Ping         => WebSocketFrame.Ping(Array.emptyByteArray)
      case ZioWebSocketFrame.Pong         => WebSocketFrame.Pong(Array.emptyByteArray)
      case c: ZioWebSocketFrame.Close     => WebSocketFrame.Close(c.status, c.reason.get)
      case ZioWebSocketFrame.Binary(data) => WebSocketFrame.Binary(data.asJava.array(), false, None)
    }

  private def frameToMessage(w: WebSocketFrame): Option[ZioWebSocketFrame] = {
    w match {
      case WebSocketFrame.Text(p, _, _)   => Some(ZioWebSocketFrame.text(p))
      case WebSocketFrame.Binary(p, _, _) => Some(ZioWebSocketFrame.Binary(ByteBuf))
      case WebSocketFrame.Ping(_)         => None
      case WebSocketFrame.Pong(_)         => None
      case WebSocketFrame.Close(_, _)     => throw WebSocketClosed(None)
    }
  }
}
