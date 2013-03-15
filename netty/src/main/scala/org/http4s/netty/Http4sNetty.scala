package org.http4s
package netty

import concurrent.{Await, Promise, Future, ExecutionContext}
import handlers.ScalaUpstreamHandler
import org.jboss.netty.channel._
import scala.util.control.Exception.allCatch
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import io.Codec
import play.api.libs.iteratee._
import java.net.{InetSocketAddress, URI, InetAddress}
import collection.JavaConverters._
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.codec.http
import http._
import scala.language.{ implicitConversions, reflectiveCalls }
import concurrent.duration._

object Routes {
   def apply(route: Route)(implicit executor: ExecutionContext = ExecutionContext.global) = new Routes(route)
}
class Routes(val route: Route)(implicit executor: ExecutionContext = ExecutionContext.global) extends Http4sNetty

abstract class Http4sNetty(implicit executor: ExecutionContext = ExecutionContext.global) extends ScalaUpstreamHandler {

  def route: Route

  import ScalaUpstreamHandler._

  val handle: UpstreamHandler = {
    case MessageReceived(ctx, req: HttpRequest, rem: InetSocketAddress) =>

      val request = toRequest(ctx, req, rem.getAddress)
      if (route.isDefinedAt(request)) {
        val ch = ctx.getChannel
        val parser = route.lift(request).getOrElse(Done(Status.NotFound(request)))
        val handler = parser flatMap (renderResponse(ch, req, _))
        val f = Enumerator[org.http4s.HttpChunk](BodyChunk(req.getContent.toByteBuffer)).run(handler)
//        val f = Enumerator[org.http4s.HttpChunk](BodyChunk(req.getContent.toByteBuffer)).run(handler)
        f onComplete {
          case scala.util.Success(_) =>
            println("prepared the response")
          case scala.util.Failure(t) =>
            println("failed sending the message")
            t.printStackTrace()

        }
        f flatMap {
          b =>
            println("response calculated")
            ctx.getChannel.write(b) flatMap { _ =>
            println("wrote response to socket")
            ctx.getChannel.close()
          } recover {
            case t =>
              t.printStackTrace
              throw t
          }
        } onComplete {
          case scala.util.Success(_) =>
            println("closed the socket")
          case scala.util.Failure(t) =>
            println("failed closing the socket")
            t.printStackTrace()

        }

//        Await.ready(Enumerator[org.http4s.HttpChunk](BodyChunk(req.getContent.toByteBuffer)).run[Unit](handler), 1 second)
      } else {
        import org.http4s.HttpVersion
        val res = new http.DefaultHttpResponse(HttpVersion.`Http/1.1`, Status.NotFound)
        res.setContent(ChannelBuffers.copiedBuffer("Not Found", Codec.UTF8.charSet))
        (NettyPromise(ctx.getChannel.write(res)) flatMap { _ =>
//          if (!http.HttpHeaders.isKeepAlive(req) && ctx.getChannel.isConnected) NettyPromise(ctx.getChannel.close())
//          else Future.successful(())
          NettyPromise(ctx.getChannel.close())
        }) onComplete {
          case _ => println("not found completed")
        }
      }

    case MessageReceived(ctx, chunk: HttpChunk, _) =>
      sys.error("Not supported")

    case ExceptionCaught(ctx, e) =>
      try {
        Option(e.getCause) foreach (t => logger.error(t.getMessage, t))
        val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR)
        resp.setContent(ChannelBuffers.copiedBuffer((e.getCause.getMessage + "\n" + e.getCause.getStackTraceString).getBytes(Codec.UTF8.charSet)))
        ctx.getChannel.write(resp).addListener(ChannelFutureListener.CLOSE)
      } catch {
        case t: Throwable =>
          logger.error(t.getMessage, t)
          allCatch(ctx.getChannel.close().await())
      }
  }

  protected def renderResponse(ctx: Channel, req: HttpRequest, responder: Responder) = {
    def closeChannel(channel: Channel) = {
//      if (!http.HttpHeaders.isKeepAlive(req) && channel.isConnected) NettyPromise(channel.close())
//      if (channel.isConnected) NettyPromise(channel.close())
//      else Future.successful(())
      val f = NettyPromise(channel.close())
      f onComplete {
        case _ => println("Request completed")
      }
      f
    }


    val stat = new HttpResponseStatus(responder.prelude.status.code, responder.prelude.status.reason)
    val resp = new http.DefaultHttpResponse(req.getProtocolVersion, stat)
    for (header <- responder.prelude.headers) {
      resp.addHeader(header.name, header.value)
    }

    val writer: (ChannelBuffer, BodyChunk) => Unit = (c, x) => c.writeBytes(x.asByteBuffer)
    val stringIteratee = Iteratee.fold[org.http4s.HttpChunk, ChannelBuffer](ChannelBuffers.dynamicBuffer(512)) {
      case (c, e: BodyChunk) =>
        println("reading body chunk")
        writer(c, e)
        c
      case (c, e: TrailerChunk) =>
        logger.warn("Not a chunked response. Trailer ignored.")
        c
    }

    responder.body ><> Enumeratee.grouped(stringIteratee) &>> Cont {
      case Input.El(buffer) =>
        resp.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.readableBytes)
        resp.setContent(buffer)
        val d = Done(resp,Input.Empty:Input[org.jboss.netty.buffer.ChannelBuffer])
        println("created done iteratee")
        d
    }

//
//    (responder.body ><> Enumeratee.grouped(stringIteratee) &>> Cont {
//      case Input.El(buffer) =>
//        resp.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.readableBytes)
//        resp.setContent(buffer)
//        val f = ctx.write(resp)
//        f onComplete {
//          case _ => closeChannel(ctx)
//        }
//        Iteratee.flatten(f.map(_ => Done((),Input.Empty:Input[org.jboss.netty.buffer.ChannelBuffer])))
//
//      case other => Error("unexepected input",other)
//    })
  }

  import HeaderNames._
  val serverSoftware = ServerSoftware("HTTP4S / Netty")

  protected def toRequest(ctx: ChannelHandlerContext, req: HttpRequest, remote: InetAddress)  = {
    val uri = URI.create(req.getUri)
    val hdrs = toHeaders(req)
    val scheme = if (ctx.getPipeline.get(classOf[SslHandler]) != null) "http" else "https" 
//    val scheme = {
//      hdrs.get(XForwardedProto).flatMap(_.value.blankOption) orElse {
//        val fhs = hdrs.get(FrontEndHttps).flatMap(_.value.blankOption)
//        fhs.filter(_ equalsIgnoreCase "ON").map(_ => "https")
//      } getOrElse {
//        if (ctx.getPipeline.get(classOf[SslHandler]) != null) "http" else "https"
//      }
//    }
    val servAddr = ctx.getChannel.getRemoteAddress.asInstanceOf[InetSocketAddress]

    RequestPrelude(
      requestMethod = Method(req.getMethod.getName),
      scriptName = "",
      pathInfo = uri.getRawPath,
      queryString = uri.getRawQuery,
      protocol = ServerProtocol(req.getProtocolVersion.getProtocolName),
      headers = hdrs,
      urlScheme = HttpUrlScheme(scheme),
      serverName = servAddr.getHostName,
      serverPort = servAddr.getPort,
      serverSoftware = serverSoftware,
      remote = remote // TODO using remoteName would trigger a lookup
    )
  }

  protected def toHeaders(req: HttpRequest) = Headers(
    (for {
      name  <- req.getHeaderNames.asScala
      value <- req.getHeaders(name).asScala
    } yield org.http4s.HttpHeaders.RawHeader(name, value)).toSeq:_*
  )
}