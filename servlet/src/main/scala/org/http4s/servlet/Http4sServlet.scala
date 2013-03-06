package org.http4s
package servlet

import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}
import org.http4s.iteratee.{Done, Iteratee, Enumerator}
import java.net.InetAddress
import scala.collection.JavaConverters._
import concurrent.{ExecutionContext,Future}
import javax.servlet.{ServletConfig, AsyncContext}
import org.http4s.Status.NotFound
import akka.util.ByteString

import Http4sServlet._
import scala.util.logging.Logged
import com.typesafe.scalalogging.slf4j.Logging

class Http4sServlet(route: Route, chunkSize: Int = DefaultChunkSize)
                   (implicit executor: ExecutionContext = ExecutionContext.global) extends HttpServlet with Logging {
  private[this] var serverSoftware: ServerSoftware = _

  override def init(config: ServletConfig) {
    serverSoftware = ServerSoftware(config.getServletContext.getServerInfo)
  }

  override def service(servletRequest: HttpServletRequest, servletResponse: HttpServletResponse) {
    val ctx = servletRequest.startAsync()
    executor.execute {
      new Runnable {
        def run() {
          handle(ctx)
        }
      }
    }
  }

  protected def handle(ctx: AsyncContext) {
    val servletRequest = ctx.getRequest.asInstanceOf[HttpServletRequest]
    val servletResponse = ctx.getResponse.asInstanceOf[HttpServletResponse]
    val request = toRequest(servletRequest)
    val parser = route.lift(request).getOrElse(Done(NotFound(request)))
    val handler = parser.flatMap { responder =>
      servletResponse.setStatus(responder.prelude.status.code, responder.prelude.status.reason)
      for (header <- responder.prelude.headers)
        servletResponse.addHeader(header.name, header.value)
      val isChunked = responder.prelude.headers.get("Transfer-Encoding").map(_.value == "chunked").getOrElse(false)
      responder.body.transform(Iteratee.foreach {
        case BodyChunk(chunk) =>
          val out = servletResponse.getOutputStream
          out.write(chunk.toArray)
          if(isChunked) out.flush()
        case t: TrailerChunk =>
          log("The servlet adapter does not implement trailers. Silently ignoring.")
      })
    }
    Enumerator.fromStream(servletRequest.getInputStream, chunkSize)
      .map[HttpChunk](BodyChunk(_))
      .run(handler)
      .onComplete(_ => ctx.complete())
  }

  protected def toRequest(req: HttpServletRequest): RequestPrelude = {
    import AsyncContext._
    RequestPrelude(
      requestMethod = Method(req.getMethod),
      scriptName = stringAttribute(req, ASYNC_CONTEXT_PATH) + stringAttribute(req, ASYNC_SERVLET_PATH),
      pathInfo = Option(stringAttribute(req, ASYNC_PATH_INFO)).getOrElse(""),
      queryString = Option(stringAttribute(req, ASYNC_QUERY_STRING)).getOrElse(""),
      protocol = ServerProtocol(req.getProtocol),
      headers = toHeaders(req),
      urlScheme = HttpUrlScheme(req.getScheme),
      serverName = req.getServerName,
      serverPort = req.getServerPort,
      serverSoftware = serverSoftware,
      remote = InetAddress.getByName(req.getRemoteAddr) // TODO using remoteName would trigger a lookup
    )
  }

  private def stringAttribute(req: HttpServletRequest, key: String): String = req.getAttribute(key).asInstanceOf[String]

  protected def toHeaders(req: HttpServletRequest): Headers = {
    val headers = for {
      name <- req.getHeaderNames.asScala
      value <- req.getHeaders(name).asScala
    } yield HttpHeaders.RawHeader(name, value)
    Headers(headers.toSeq : _*)
  }
}

object Http4sServlet {
  private[servlet] val DefaultChunkSize = Http4sConfig.getInt("org.http4s.servlet.default-chunk-size")
}