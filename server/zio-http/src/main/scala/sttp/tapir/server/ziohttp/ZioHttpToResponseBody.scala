package sttp.tapir.server.ziohttp

import sttp.capabilities.zio.ZioStreams
import sttp.model.HasHeaders
import sttp.tapir.server.interpreter.ToResponseBody
import sttp.tapir.{CodecFormat, RawBodyType, WebSocketBodyOutput}
import zio.Chunk
import zio.blocking.Blocking
import zio.stream.{Stream, ZStream}

import java.nio.charset.Charset

class ZioHttpToResponseBody extends ToResponseBody[ZioHttpResponseBody, ZioStreams] {
  override val streams: ZioStreams = ZioStreams

  override def fromRawValue[R](v: R, headers: HasHeaders, format: CodecFormat, bodyType: RawBodyType[R]): ZioHttpResponseBody =
    Right(rawValueToEntity(bodyType, v))

  override def fromStreamValue(
      v: streams.BinaryStream,
      headers: HasHeaders,
      format: CodecFormat,
      charset: Option[Charset]
  ): ZioHttpResponseBody =
    Right(v)

  override def fromWebSocketPipe[REQ, RESP](
      pipe: streams.Pipe[REQ, RESP],
      o: WebSocketBodyOutput[streams.Pipe[REQ, RESP], REQ, RESP, _, ZioStreams]
  ): ZioHttpResponseBody =
    Left(ZioHttpWebSockets.pipeToBody(pipe, o))

  private def rawValueToEntity[CF <: CodecFormat, R](bodyType: RawBodyType[R], r: R): ZStream[Blocking, Throwable, Byte] = {
    bodyType match {
      case RawBodyType.StringBody(charset) =>
        ZStream.fromIterable(r.toString.getBytes(charset))
      case RawBodyType.ByteArrayBody  => Stream.fromChunk(Chunk.fromArray(r))
      case RawBodyType.ByteBufferBody => Stream.fromChunk(Chunk.fromByteBuffer(r))
      case RawBodyType.InputStreamBody =>
        ZStream.fromInputStream(r)
      case RawBodyType.FileBody =>
        ZStream.fromFile(r.toPath)
      case RawBodyType.MultipartBody(_, _) =>
        Stream.empty
    }
  }
}
