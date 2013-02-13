package org.http4s
package servlet

import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}
import play.api.libs.iteratee.{Iteratee, Enumerator}
import java.net.InetAddress
import scala.collection.JavaConverters._
import concurrent.{ExecutionContext,Future}
import javax.servlet.AsyncContext

class Http4sServlet(route: Route, chunkSize: Int = 32 * 1024)(implicit executor: ExecutionContext = ExecutionContext.global) extends HttpServlet {
  override def service(req: HttpServletRequest, resp: HttpServletResponse) {
    val request = toRequest(req)
    val ctx = req.startAsync()

    val handler: Future[Responder[Chunk]] = Future.successful() flatMap { Unit =>
      route.lift(request).getOrElse{
        Future.successful(ResponderGenerators.genRouteNotFound(request))
      }
    }

    handler.onSuccess { case responder =>
      renderResponse(responder, resp, ctx)
    }

    handler.onFailure{ case t =>
      renderResponse(ResponderGenerators.genRouteErrorResponse(t), resp, ctx)
    }

  }

  protected def renderResponse(responder: Responder[Chunk], resp: HttpServletResponse, ctx: AsyncContext) {
    resp.setStatus(responder.statusLine.code, responder.statusLine.reason)
    for (header <- responder.headers) {
      resp.addHeader(header.name, header.value)
    }
    val it = Iteratee.foreach[Chunk] { chunk =>
      resp.getOutputStream.write(chunk)
      resp.getOutputStream.flush()
    }
    responder.body.run(it).onComplete {
      case _ => ctx.complete()
    }
  }

  protected def toRequest(req: HttpServletRequest): Request[Chunk] =
    Request(
      requestMethod = Method(req.getMethod),
      scriptName = req.getContextPath + req.getServletPath,
      pathInfo = Option(req.getPathInfo).getOrElse(""),
      queryString = Option(req.getQueryString).getOrElse(""),
      protocol = ServerProtocol(req.getProtocol),
      headers = toHeaders(req),
      body = Enumerator.fromStream(req.getInputStream, chunkSize),
      urlScheme = HttpUrlScheme(req.getScheme),
      serverName = req.getServerName,
      serverPort = req.getServerPort,
      serverSoftware = ServerSoftware(getServletContext.getServerInfo),
      remote = InetAddress.getByName(req.getRemoteAddr) // TODO using remoteName would trigger a lookup
    )

  protected def toHeaders(req: HttpServletRequest): Headers = {
    val headers = for {
      name <- req.getHeaderNames.asScala
      value <- req.getHeaders(name).asScala
    } yield HttpHeaders.RawHeader(name, value)
    Headers(headers.toSeq : _*)
  }
}
