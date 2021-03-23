package zhttp.http

import zhttp.core.JFullHttpResponse
import zhttp.socket.WebSocketFrame
import zio.Task
import zio.stream.ZStream

import java.io.{PrintWriter, StringWriter}

// RESPONSE
sealed trait Response[-R, +E] extends Product with Serializable { self => }

object Response {
  private val defaultStatus  = Status.OK
  private val defaultHeaders = Nil
  private val defaultContent = HttpContent.Empty

  // Constructors
  final case class HttpResponse[R](status: Status, headers: List[Header], content: HttpContent[R, String])
      extends Response[R, Nothing]

  final case class SocketResponse[R, E](
    socket: WebSocketFrame => ZStream[R, E, WebSocketFrame],
    subProtocol: Option[String],
  ) extends Response[R, E]

  // Helpers

  /**
   * Creates a new Http Response
   */
  def http(
    status: Status = defaultStatus,
    headers: List[Header] = defaultHeaders,
    content: HttpContent[Any, String] = defaultContent,
  ): UResponse =
    HttpResponse(status, headers, content)

  /**
   * Creates a new WebSocket Response
   */
  def socket(subProtocol: Option[String])(socket: WebSocketFrame => ZStream[Any, Nothing, WebSocketFrame]): UResponse =
    SocketResponse(socket, subProtocol)

  def fromHttpError(error: HttpError): UResponse = {
    error match {
      case cause: HTTPErrorWithCause =>
        http(
          error.status,
          Nil,
          HttpContent.Complete(cause.cause match {
            case Some(throwable) =>
              val sw = new StringWriter
              throwable.printStackTrace(new PrintWriter(sw))
              s"${cause.message}:\n${sw.toString}"
            case None            => s"${cause.message}"
          }),
        )
      case _                         => http(error.status, Nil, HttpContent.Complete(error.message))
    }

  }

  def ok: UResponse = http(Status.OK)

  def text(text: String): UResponse =
    http(
      content = HttpContent.Complete(text),
      headers = List(Header.contentTypeTextPlain),
    )

  def jsonString(data: String): UResponse =
    http(
      content = HttpContent.Complete(data),
      headers = List(Header.contentTypeJson),
    )

  def status(status: Status): UResponse = http(status)

  def fromJFullHttpResponse(jRes: JFullHttpResponse): Task[UResponse] = Task {
    val status  = Status.fromJHttpResponseStatus(jRes.status())
    val headers = Header.parse(jRes.headers())
    val content = HttpContent.Complete(jRes.content().toString(HTTP_CHARSET))

    Response.http(status, headers, content)
  }
}
