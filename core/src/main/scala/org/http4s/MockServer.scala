package org.http4s

import scala.language.reflectiveCalls

import concurrent.{Await, ExecutionContext, Future}
import concurrent.duration._
import org.http4s.iteratee.{Enumeratee, Enumerator, Iteratee}

class MockServer(route: Route)(implicit executor: ExecutionContext = ExecutionContext.global) {
  import MockServer.Response

  def apply(req: RequestPrelude, enum: Enumerator[HttpChunk]): Future[Response] = {
    try {
      route.lift(req).fold(Future.successful(onNotFound)) { parser =>
        val it: Iteratee[HttpChunk, Response] = parser.flatMap { responder =>
          val responseBodyIt: Iteratee[BodyChunk, BodyChunk] = Iteratee.consume(executor)()
          responder.body ><> BodyParser.whileBodyChunk &>> responseBodyIt map { bytes: BodyChunk =>
            Response(responder.prelude.status, responder.prelude.headers, body = bytes.toArray)
          }
        }
        enum.run(it)
      }
    } catch {
      case t: Throwable => Future.successful(onError(t))
    }
  }

  def response(req: RequestPrelude,
               body: Enumerator[HttpChunk] = Enumerator.eof,
               wait: Duration = 5.seconds): MockServer.Response = {
    Await.result(apply(req, body), 5.seconds)
  }

  def onNotFound: MockServer.Response = Response(statusLine = Status.NotFound)

  def onError: PartialFunction[Throwable, Response] = {
    case e: Exception =>
      e.printStackTrace()
      Response(statusLine = Status.InternalServerError)
  }
}

object MockServer {
  private[MockServer] val emptyBody = Array.empty[Byte]   // Makes direct Response comparison possible

  case class Response(
    statusLine: Status = Status.Ok,
    headers: Headers = Headers.Empty,
    body: Array[Byte] = emptyBody
  )
}
