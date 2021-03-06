package org.http4s

import java.io.File
import java.net.{URI, InetAddress}

case class RequestPrelude(
                           requestMethod: Method = Methods.Get,
                           scriptName: String = "",
                           pathInfo: String = "",
                           queryString: String = "",
                           pathTranslated: Option[File] = None,
                           protocol: ServerProtocol = HttpVersion.`Http/1.1`,
                           headers: HeaderCollection = HeaderCollection.empty,
                           urlScheme: UrlScheme = HttpUrlScheme.Http,
                           serverName: String = InetAddress.getLocalHost.getHostName,
                           serverPort: Int = 80,
                           serverSoftware: ServerSoftware = ServerSoftware.Unknown,
                           remote: InetAddress = InetAddress.getLocalHost,
                           attributes: AttributeMap = AttributeMap.empty
                           ) {
  def contentLength: Option[Int] = headers.get(Headers.ContentLength).map(_.length)

  def contentType: Option[ContentType] = headers.get(Headers.ContentType).map(_.contentType)

  def charset: Charset = contentType.map(_.charset) getOrElse Charsets.`ISO-8859-1`

  val uri: URI = new URI(urlScheme.toString, null, serverName, serverPort, scriptName+pathInfo, queryString, null)

  lazy val authType: Option[AuthType] = None

  lazy val remoteAddr = remote.getHostAddress
  lazy val remoteHost = remote.getHostName

  lazy val remoteUser: Option[String] = None

  /* Attributes proxy */
  def updated[T](key: AttributeKey[T], value: T) = copy(attributes = attributes.put(key, value))
  def apply[T](key: AttributeKey[T]): T = attributes(key)
  def get[T](key: AttributeKey[T]): Option[T] = attributes.get(key)
  def getOrElse[T](key: AttributeKey[T], default: => T) = get(key).getOrElse(default)
  def +[T](kv: (AttributeKey[T], T)) = updated(kv._1, kv._2)
  def -[T](key: AttributeKey[T]) = copy(attributes = attributes.remove(key))
  def contains[T](key: AttributeKey[T]): Boolean = attributes.contains(key)
}
