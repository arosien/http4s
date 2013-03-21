package org.http4s

import scala.language.implicitConversions
import concurrent.{ExecutionContext, Future}
import akka.util.ByteString
import util.Spool

trait Writable[-A] {
  def contentType: ContentType
  def toBody(a: A): (Spool[HttpChunk], Option[Int])
}

trait SimpleWritable[-A] extends Writable[A] {
  def asByteString(data: A): ByteString
  override def toBody(a: A): (Spool[HttpChunk], Option[Int]) = {
    val bs = asByteString(a)
    (Writable.sendByteString(bs), Some(bs.length))
  }
}

object Writable {
  private[http4s] def sendByteString(data: ByteString): Spool[HttpChunk] =
    Spool.cons(BodyChunk(data), Future.successful(Spool.empty))
//
//  private[http4s] def sendFuture[T](f: Future[T])(implicit ec: ExecutionContext): Enumeratee[T, T] =
//    new Enumeratee[T, T] {
//      def applyOn[A](inner: Iteratee[T, A]): Iteratee[T, Iteratee[T, A]] =
//        Done(Iteratee.flatten(f.flatMap{ d => inner.feed(Input.El(d))}))
//    }
//
//  private[http4s] def sendEnumerator[F, T](enumerator: Enumerator[T]): Enumeratee[F, T] = new Enumeratee[F, T] {
//    def applyOn[A](inner: Iteratee[T, A]): Iteratee[F, Iteratee[T, A]] =
//      Done(Iteratee.flatten(enumerator(inner)), Input.Empty)
//  }
  // Simple types defined
  implicit def stringWritable(implicit charset: HttpCharset = HttpCharsets.`UTF-8`) =
    new SimpleWritable[String] {
      def contentType: ContentType = ContentType.`text/plain`.withCharset(charset)
      def asByteString(s: String) = ByteString(s, charset.nioCharset.name)
    }
//
//  implicit def htmlWritable(implicit charset: HttpCharset = HttpCharsets.`UTF-8`) =
//    new SimpleWritable[xml.Elem] {
//      def contentType: ContentType = ContentType(MediaTypes.`text/html`).withCharset(charset)
//      def asByteString(s: xml.Elem) = ByteString(s.buildString(false), charset.nioCharset.name)
//    }
//
//  implicit def intWritable(implicit charset: HttpCharset = HttpCharsets.`UTF-8`) =
//    new SimpleWritable[Int] {
//      def contentType: ContentType = ContentType.`text/plain`.withCharset(charset)
//      def asByteString(i: Int): ByteString = ByteString(i.toString, charset.nioCharset.name)
//    }
//
//  implicit def ByteStringWritable =
//    new SimpleWritable[ByteString] {
//      def contentType: ContentType = ContentType.`application/octet-stream`
//      def asByteString(ByteString: ByteString) = ByteString
//    }
//
//  // More complex types can be implements in terms of simple types
//  implicit def traversableWritable[A](implicit writable: SimpleWritable[A]) =
//    new Writable[TraversableOnce[A]] {
//      def contentType: ContentType = writable.contentType
//      override def toBody(as: TraversableOnce[A]) = {
//        val bs = as.foldLeft(ByteString.empty) { (acc, a) => acc ++ writable.asByteString(a) }
//        (sendByteString(bs), Some(bs.length))
//      }
//    }
//
//  implicit def enumerateeWritable =
//  new Writable[Enumeratee[HttpChunk, HttpChunk]] {
//    def contentType = ContentType.`application/octet-stream`
//    override def toBody(a: Enumeratee[HttpChunk, HttpChunk])= (a, None)
//  }
//
//  implicit def enumeratorWritable[A](implicit writable: SimpleWritable[A]) =
//  new Writable[Enumerator[A]] {
//    def contentType = writable.contentType
//    override def toBody(a: Enumerator[A]) = (sendEnumerator(a.map[HttpChunk]{ i => BodyChunk(writable.asByteString(i)) }), None)
//  }
//
//  implicit def futureWritable[A](implicit writable: SimpleWritable[A], ec: ExecutionContext) =
//  new Writable[Future[A]] {
//    def contentType = writable.contentType
//    override def toBody(f: Future[A]) = (sendFuture(f.map{ d => BodyChunk(writable.asByteString(d))}), None)
//  }

}
