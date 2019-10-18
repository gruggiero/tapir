package tapir.server.play

import play.api.mvc.{AnyContent, Request}
import tapir.internal.server.DecodeInputsContext
import tapir.model.{Method, ServerRequest}

private[play] class PlayDecodeInputContext(request: Request[AnyContent], pathConsumed: Int = 0) extends DecodeInputsContext {
  override def method: Method = Method(request.method.toUpperCase())

  override def nextPathSegment: (Option[String], DecodeInputsContext) = {
    val path = request.path.drop(pathConsumed)
    val nextStart = path.dropWhile(_ == '/')
    val segment = nextStart.split("/", 2) match {
      case Array("")   => None
      case Array(s)    => Some(s)
      case Array(s, _) => Some(s)
    }
    val charactersConsumed = segment.map(_.length).getOrElse(0) + (path.length - nextStart.length)

    (segment, new PlayDecodeInputContext(request, pathConsumed + charactersConsumed))
  }
  override def header(name: String): List[String] = request.headers.toMap.get(name).toList.flatten
  override def headers: Seq[(String, String)] = request.headers.headers
  override def queryParameter(name: String): Seq[String] = request.queryString.get(name).toSeq.flatten
  override def queryParameters: Map[String, Seq[String]] = request.queryString
  override def bodyStream: Any = request.body

  override def serverRequest: ServerRequest = new PlayServerRequest(request)
}
