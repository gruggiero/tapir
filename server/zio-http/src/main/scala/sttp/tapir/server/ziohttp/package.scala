package sttp.tapir.server

import zhttp.socket.{Socket, WebSocketFrame}
import zio.blocking.Blocking
import zio.stream.ZStream

package object ziohttp {
  private[ziohttp] type ZioHttpResponseBody =
    Either[Socket[Any, Nothing, WebSocketFrame, WebSocketFrame], ZStream[Blocking, Throwable, Byte]]
}
