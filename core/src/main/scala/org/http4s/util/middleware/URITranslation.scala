package org.http4s.util
package middleware

import org.http4s.{Responder, HttpChunk, Route, RequestPrelude}
import play.api.libs.iteratee.Iteratee
import concurrent.Future

/**
 * @author Bryce Anderson
 *         Created on 3/9/13 at 10:43 AM
 */

object URITranslation {

  def TranslateRoot(prefix: String)(in: Route): Route = {
    val newPrefix = if (!prefix.startsWith("/")) "/" + prefix else prefix
    val trans: String => String = { str =>
      if(str.startsWith(newPrefix)) str.substring(prefix.length) else str
    }
    TranslatePath(trans)(in)
  }

  def TranslatePath(trans: String => String)(in: Route): Route = new Route {
    def apply(req: RequestPrelude): Spool[HttpChunk] => Future[Responder] =
        in(req.copy(pathInfo = trans(req.pathInfo)))

    def isDefinedAt(req: RequestPrelude): Boolean =
      in.isDefinedAt(req.copy(pathInfo = trans(req.pathInfo)))
  }

}
