package org.http4s.util

import scala.collection.mutable.ArrayBuffer

import concurrent.{ExecutionContext, Await, Promise, Future}
import scala.util.{Failure, Success}
import concurrent.duration.FiniteDuration

/**
 * A spool is an asynchronous stream. It more or less
 * mimics the scala {{Stream}} collection, but with cons
 * cells that have deferred tails.
 *
 * Construction is done with Spool.cons, Spool.empty.  Convenience
 * syntax like that of [[scala.Stream]] is provided.  In order to use
 * these operators for deconstruction, they must be imported
 * explicitly ({{import Spool.{*::, **::}}})
 *
 * {{{
 *   def fill(rest: Promise[Spool[Int]]) {
 *     asyncProcess foreach { result =>
 *       if (result.last) {
 *         rest() = Return(result **:: Spool.empty)
 *       } else {
 *         val next = new Promise[Spool[Int]]
 *         rest() = Return(result *:: next)
 *         fill(next)
 *       }
 *     }
 *   }
 *   val rest = new Promise[Spool[Int]]
 *   fill(rest)
 *   firstElem *:: rest
 * }}}
 */
sealed trait Spool[+A] {
  import Spool.{cons, empty}

  def isEmpty: Boolean

  /**
   * The first element of the spool. Invalid for empty spools.
   */
  def head: A

  /**
   * The (deferred) tail of the spool. Invalid for empty spools.
   */
  def tail: Future[Spool[A]]

  /**
   * Apply {{f}} for each item in the spool, until the end.  {{f}} is
   * applied as the items become available.
   */
  def foreach[B](f: A => B)(implicit executor: ExecutionContext) = foreachElem { _ foreach(f) }

  /**
   * A version of {{foreach}} that wraps each element in an
   * {{Option}}, terminating the stream (EOF or failure) with
   * {{None}}.
   */
  def foreachElem[B](f: Option[A] => B)(implicit executor: ExecutionContext) {
    if (!isEmpty) {
      f(Some(head))
      // note: this hack is to avoid deep
      // stacks in case a large portion
      // of the stream is already defined
      var next = tail
      while (next.value.fold(false)(_.isSuccess) && !next.value.get.get.isEmpty) {
        f(Some(next.value.get.get.head))
        next = next.value.get.get.tail
      }
      next.onComplete {
        case Success(s) => s.foreachElem(f)
        case Failure(e) => f(None)
      }
    } else {
      f(None)
    }
  }

  /**
   * The standard Scala collect, in order to implement map & filter.
   *
   * It may seem unnatural to return a Future[…] here, but we cannot
   * know whether the first element exists until we have applied its
   * filter.
   */
  def collect[B](f: PartialFunction[A, B])(implicit executor: ExecutionContext): Future[Spool[B]]

  def map[B](f: A => B)(implicit executor: ExecutionContext): Spool[B] = {
    val s = collect { case x => f(x) }
    require(s.isCompleted)
    s.value.get.get
  }

  def filter(f: A => Boolean)(implicit executor: ExecutionContext): Future[Spool[A]] = collect {
    case x if f(x) => x
  }

  /**
   * Concatenates two spools.
   */
  def ++[B >: A](that: Spool[B])(implicit executor: ExecutionContext): Spool[B] =
    if (isEmpty) that else cons(head: B, tail map { _ ++ that })

  /**
   * Concatenates two spools.
   */
  def ++[B >: A](that: Future[Spool[B]])(implicit executor: ExecutionContext): Future[Spool[B]] =
    if (isEmpty) that else Future.successful(cons(head: B, tail flatMap { _ ++ that }))

  /**
   * Applies a function that generates a spool to each element in this spool,
   * flattening the result into a single spool.
   */
  def flatMap[B](f: A => Future[Spool[B]])(implicit executor: ExecutionContext): Future[Spool[B]] =
    if (isEmpty) Future.successful(empty[B])
    else f(head) flatMap { _ ++ (tail flatMap { _ flatMap f }) }

  /**
   * Fully buffer the spool to a {{Seq}}.  The returned future is
   * satisfied when the entire result is ready.
   */
  def toSeq(implicit executor: ExecutionContext): Future[Seq[A]] = {
    val p = Promise[Seq[A]]
    val as = new ArrayBuffer[A]
    foreachElem {
      case Some(a) => as += a
      case None => p.success(as)
    }
    p.future
  }
}

object Spool {
  private[Spool]
  case class Cons[A](value: A, next: Future[Spool[A]])
    extends Spool[A]
  {
    def isEmpty = false
    def head = value
    def tail = next
    def collect[B](f: PartialFunction[A, B])(implicit executor: ExecutionContext) = {
      val next_ = next flatMap { _.collect(f) }
      if (f.isDefinedAt(head)) Future.successful(Cons(f(head), next_))
      else next_
    }

    override def toString = "Cons(%s, %c)".format(head, if (tail.isCompleted) '*' else '?')
  }

  private[Spool] object Empty extends Spool[Nothing] {
    def isEmpty = true
    def head = throw new NoSuchElementException("stream is empty")
    def tail = throw new UnsupportedOperationException("stream is empty")
    def collect[B](f: PartialFunction[Nothing, B])(implicit executor: ExecutionContext) = Future.successful(this)
    override def toString = "Empty"
  }

  /**
   * Cons a value & (possibly deferred) tail to a new {{Spool}}.
   */
  def cons[A](value: A, next: Future[Spool[A]]): Spool[A] = Cons(value, next)
  def cons[A](value: A, nextStream: Spool[A]): Spool[A] = Cons(value, Future.successful(nextStream))

  /**
   * The empty spool.
   */
  def empty[A]: Spool[A] = Empty

  /**
   * Syntax support.  We retain different constructors for future
   * resolving vs. not.
   *
   * *:: constructs and deconstructs deferred tails
   * **:: constructs and deconstructs eager tails
   */

  class Syntax[A](tail: => Future[Spool[A]]) {
    def *::(head: A) = cons(head, tail)
  }

  implicit def syntax[A](s: Future[Spool[A]]) = new Syntax(s)

  object *:: {
    def unapply[A](s: Spool[A]): Option[(A, Future[Spool[A]])] = {
      if (s.isEmpty) None
      else Some((s.head, s.tail))
    }
  }
  class Syntax1[A](tail: => Spool[A]) {
    def **::(head: A) = cons(head, tail)
  }

  implicit def syntax1[A](s: Spool[A]) = new Syntax1(s)

  object **:: {
    def unapply[A](s: Spool[A])(implicit timeout: FiniteDuration): Option[(A, Spool[A])] = {
      if (s.isEmpty) None
      else Some((s.head, Await.result(s.tail, timeout)))
    }
  }
}