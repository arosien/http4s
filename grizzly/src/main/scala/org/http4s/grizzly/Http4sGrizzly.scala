package org.http4s
package grizzly

import org.glassfish.grizzly.http.server.{Response,Request=>GrizReq, HttpHandler}

import java.net.InetAddress
import scala.collection.JavaConverters._
import concurrent.ExecutionContext
import play.api.libs.iteratee.{Concurrent, Done}
import org.http4s.Status.{InternalServerError, NotFound}
import org.glassfish.grizzly.ReadHandler
import org.http4s.Status.NotFound
import org.http4s.RequestPrelude

/**
 * @author Bryce Anderson
 */

class Http4sGrizzly(route: Route, chunkSize: Int = 32 * 1024)(implicit executor: ExecutionContext = ExecutionContext.global) extends HttpHandler {

  override def service(req: GrizReq, resp: Response) {
    resp.suspend()  // Suspend the response until we close it
    val request = toRequest(req)

    val parser = try {
      route.lift(request).getOrElse(Done(NotFound(request)))
    } catch { case t: Throwable => Done[HttpChunk, Responder](InternalServerError(t)) }

    val handler = parser.flatMap { case responder: Responder =>
      resp.setStatus(responder.prelude.status.code, responder.prelude.status.reason)
      for (header <- responder.prelude.headers)
        resp.addHeader(header.name, header.value)

      import HttpEncodings.chunked
      val isChunked = responder.prelude.headers.get(HttpHeaders.TransferEncoding).map(_.coding.matches(chunked)).getOrElse(false)
      val out = new OutputIteratee(resp.getNIOOutputStream, isChunked)
      responder.body.transform(out)

    case _: SocketResponder => sys.error(s"Http4sGrizzly captured a websocket route: ${req.getRequestURI} ")
    }

    var canceled = false
    Concurrent.unicast[HttpChunk]({
      channel =>
        val bytes = new Array[Byte](chunkSize)
        val is = req.getNIOInputStream
        def push() = {
          while(is.available() > 0 && (!canceled)) {
            val readBytes = is.read(bytes,0,chunkSize)
            channel.push(BodyChunk.fromArray(bytes, 0, readBytes))
          }
        }
        is.notifyAvailable( new ReadHandler { self =>
          def onDataAvailable() { push(); is.notifyAvailable(self) }
          def onError(t: Throwable) {}
          def onAllDataRead() { push(); channel.eofAndEnd() }
        })
      },
      {canceled = true}
    ).run(handler)
    .onComplete{ _ => resp.resume() }
  }

  protected def toRequest(req: GrizReq): RequestPrelude = {
    val input = req.getNIOInputStream
    RequestPrelude(
      requestMethod = Method(req.getMethod.toString),
      scriptName = req.getContextPath, // + req.getServletPath,
      pathInfo = Option(req.getPathInfo).getOrElse(""),
      queryString = Option(req.getQueryString).getOrElse(""),
      protocol = ServerProtocol(req.getProtocol.getProtocolString),
      headers = toHeaders(req),
      urlScheme = HttpUrlScheme(req.getScheme),
      serverName = req.getServerName,
      serverPort = req.getServerPort,
      serverSoftware = ServerSoftware(req.getServerName),
      remote = InetAddress.getByName(req.getRemoteAddr) // TODO using remoteName would trigger a lookup
    )
  }

  protected def toHeaders(req: GrizReq): HttpHeaders = {
    val headers = for {
      name <- req.getHeaderNames.asScala
      value <- req.getHeaders(name).asScala
    } yield HttpHeaders.RawHeader(name, value)
    HttpHeaders(headers.toSeq : _*)
  }
}
