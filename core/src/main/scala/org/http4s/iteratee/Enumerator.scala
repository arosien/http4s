package org.http4s.iteratee

import concurrent.{ExecutionContext, Future, Promise}
import scala.util.{ Try, Success, Failure }
import scala.language.reflectiveCalls

/**
 * A producer which pushes input to an [[org.http4s.iteratee.Iteratee]].
 */
trait Enumerator[E] {
  parent =>
   
  /**
   * Attaches this Enumerator to an [[org.http4s.iteratee.Iteratee]], driving the
   * Iteratee to (asynchronously) consume the input. The Iteratee may enter its
   * [[org.http4s.iteratee.Done]] or [[org.http4s.iteratee.Error]]
   * state, or it may be left in a [[org.http4s.iteratee.Cont]] state (allowing it
   * to consume more input after that sent by the enumerator).
   *
   * If the Iteratee reaches a [[org.http4s.iteratee.Done]] state, it will
   * contain a computed result and the remaining (unconsumed) input.
   */
  def apply[A](i: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]]
 
  /**
   * Alias for `apply`, produces input driving the given [[org.http4s.iteratee.Iteratee]]
   * to consume it.
   */
  def |>>[A](i: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = apply(i)

   /**
    * Attaches this Enumerator to an [[org.http4s.iteratee.Iteratee]], driving the
    * Iteratee to (asynchronously) consume the enumerator's input. If the Iteratee does not
    * reach a [[org.http4s.iteratee.Done]] or [[org.http4s.iteratee.Error]]
    * state when the Enumerator finishes, this method forces one of those states by
    * feeding `Input.EOF` to the Iteratee.
    *
    * If the iteratee is left in a [[org.http4s.iteratee.Done]]
    * state then the promise is completed with the iteratee's result.
    * If the iteratee is left in an [[org.http4s.iteratee.Error]] state, then the
    * promise is completed with a [[java.lang.RuntimeException]] containing the
    * iteratee's error message.
    *
    * Unlike `apply` or `|>>`, this method does not allow you to access the
    * unconsumed input.
    */
  def |>>>[A](i: Iteratee[E, A])(implicit executor: ExecutionContext): Future[A] = apply(i).flatMap(_.run)

  /**
   * Alias for `|>>>`; drives the iteratee to consume the enumerator's
   * input, adding an Input.EOF at the end of the input. Returns either a result
   * or an exception.
   */
  def run[A](i: Iteratee[E, A])(implicit executor: ExecutionContext): Future[A] = |>>>(i)
  
 /** 
  * A variation on `apply` or `|>>` which returns the state of the iteratee rather
  * than the iteratee itself. This can make your code a little shorter.
  */
  def |>>|[A](i: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Step[E,A]] = apply(i).flatMap(_.unflatten)
  
  /**
   * Sequentially combine this Enumerator with another Enumerator. The resulting enumerator
   * will produce both input streams, this one first, then the other.
   * Note: the current implementation will break if the first enumerator
   * produces an Input.EOF.
   */
  def andThen(e: Enumerator[E]): Enumerator[E] = new Enumerator[E] {
    def apply[A](i: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = parent.apply(i).flatMap(e.apply(_)(executor)) //bad implementation, should remove Input.EOF in the end of first
  }

  def interleave[B >: E](other: Enumerator[B]): Enumerator[B] = Enumerator.interleave(this, other)

  /**
   * Alias for interleave
   */
  def >-[B >: E](other: Enumerator[B]): Enumerator[B] = interleave(other)

  /**
   * Compose this Enumerator with an Enumeratee. Alias for through
   */
  def &>[To](enumeratee: Enumeratee[E, To]): Enumerator[To] = new Enumerator[To] {

    def apply[A](i: Iteratee[To, A])(implicit executor: ExecutionContext): Future[Iteratee[To, A]] = {
      val transformed = enumeratee.applyOn(i)
      val xx = parent |>> transformed
      xx.flatMap(_.run)

    }
  }

  def onDoneEnumerating(callback: => Unit) = new Enumerator[E]{

    def apply[A](it:Iteratee[E,A])(implicit executor: ExecutionContext): Future[Iteratee[E,A]] = parent.apply(it).map{ a => callback; a}

  }

  /**
   * Compose this Enumerator with an Enumeratee
   */
  def through[To](enumeratee: Enumeratee[E, To]): Enumerator[To] = &>(enumeratee)

 /**
  * Alias for `andThen`
  */
  def >>>(e: Enumerator[E]): Enumerator[E] = andThen(e)
  
  /**
   * maps the given function f onto parent Enumerator
   * @param f function to map
   * @return enumerator
   */
  def map[U](f: E => U): Enumerator[U] = parent &> Enumeratee.map[E](f)

  def mapInput[U](f: Input[E] => Input[U]) = parent &> Enumeratee.mapInput[E](f)

  /**
   * flatmaps the given function f onto parent Enumerator
   * @param f function to map
   * @return enumerator
   */
  def flatMap[U](f: E => Enumerator[U]): Enumerator[U] = {
    new Enumerator[U] {
      def apply[A](iteratee: Iteratee[U, A])(implicit executor: ExecutionContext): Future[Iteratee[U, A]] = {

        val folder = Iteratee.fold2[E, Iteratee[U, A]](iteratee)((it, e) => f(e)(it).flatMap(newIt => Iteratee.isDoneOrError(newIt).map((newIt, _))))
        parent(folder).flatMap(_.run)
      }
    }
  }

}
/**
 * Enumerator is the source that pushes input into a given iteratee. 
 * It enumerates some input into the iteratee and eventually returns the new state of that iteratee. 
 */
object Enumerator {

  def flatten[E](eventuallyEnum: Future[Enumerator[E]]): Enumerator[E] = new Enumerator[E] {

    def apply[A](it: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = eventuallyEnum.flatMap(_.apply(it))

  }

  /** Creates an enumerator which produces the one supplied
   * input and nothing else. This enumerator will NOT
   * automatically produce Input.EOF after the given input.
   */
  def enumInput[E](e: Input[E]) = new Enumerator[E] {
    def apply[A](i: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] =
      i.fold{ 
        case Step.Cont(k) => Future(k(e))
        case _ =>  Future.successful(i)
      }
  }

  /**
   * Interleave multiple enumerators together.
   *
   * Interleaving is done based on whichever enumerator next has input ready, if multiple have input ready, the order
   * is undefined.
   */
  def interleave[E](e1: Enumerator[E], es: Enumerator[E] *): Enumerator[E] = interleave(e1 +: es)

  /**
   * Interleave multiple enumerators together.
   *
   * Interleaving is done based on whichever enumerator next has input ready, if multiple have input ready, the order
   * is undefined.
   */
  def interleave[E](es: Seq[Enumerator[E]]): Enumerator[E] = new Enumerator[E] {

    import scala.concurrent.stm._

    def apply[A](it: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = {

      val iter: Ref[Iteratee[E, A]] = Ref(it)
      val attending: Ref[Option[Seq[Boolean]]] = Ref(Some(es.map(_ => true)))
      val result = Promise[Iteratee[E, A]]()

      def redeemResultIfNotYet(r:Iteratee[E, A]){
        if (attending.single.transformIfDefined{ case Some(_) => None})
            result.success(r)
      }

      def iteratee[EE <: E](f: Seq[Boolean] => Seq[Boolean]): Iteratee[EE, Unit] = {
        def step(in: Input[EE]): Iteratee[EE, Unit] = {

          val p = Promise[Iteratee[E, A]]()
          val i = iter.single.swap(Iteratee.flatten(p.future))
          in match {
            case Input.El(_) | Input.Empty =>

              val nextI = i.fold {

                case Step.Cont(k) =>
                  val n = k(in)
                  n.fold {
                    case Step.Cont(kk) =>
                      p.success(Cont(kk))
                      Future.successful(Cont(step))
                    case _ => 
                      p.success(n)
                      Future.successful(Done((), Input.Empty: Input[EE]))
                  }
                case _ =>
                  p.success(i)
                  Future.successful(Done((),Input.Empty: Input[EE]))

              }
              Iteratee.flatten(nextI)
            case Input.EOF => {
              if(attending.single.transformAndGet { _.map(f) }.forall(_ ==  false)){
                p.complete(Try(Iteratee.flatten(i.feed(Input.EOF))))
              } else {
                p.success(i)
              }
              Done((), Input.Empty)
            }
          }
        }
        Cont(step)
      }
      val ps = es.zipWithIndex.map{ case (e,index) => e |>> iteratee[E](_.patch(index,Seq(true),1))}
                     .map(_.flatMap(_.pureFold(any => ())))

      Future.sequence(ps).onComplete {
        case Success(_) => 
          redeemResultIfNotYet(iter.single())
        case Failure(e) => result.failure(e)

      }

      result.future
    }

  }

  /**
   * Interleave two enumerators together.
   *
   * Interleaving is done based on whichever enumerator next has input ready, if both have input ready, the order is
   * undefined.
   */
  def interleave[E1, E2 >: E1](e1: Enumerator[E1], e2: Enumerator[E2]): Enumerator[E2] = new Enumerator[E2] {

    import scala.concurrent.stm._

    def apply[A](it: Iteratee[E2, A])(implicit executor: ExecutionContext): Future[Iteratee[E2, A]] = {

      val iter: Ref[Iteratee[E2, A]] = Ref(it)
      val attending: Ref[Option[(Boolean, Boolean)]] = Ref(Some(true, true))
      val result = Promise[Iteratee[E2, A]]()

      def redeemResultIfNotYet(r:Iteratee[E2, A]){
        if (attending.single.transformIfDefined{ case Some(_) => None})
            result.success(r)
      }

      def iteratee[EE <: E2](f: ((Boolean, Boolean)) => (Boolean, Boolean)): Iteratee[EE, Unit] = {
        def step(in: Input[EE]): Iteratee[EE, Unit] = {

          val p = Promise[Iteratee[E2, A]]()
          val i = iter.single.swap(Iteratee.flatten(p.future))
          in match {
            case Input.El(_) | Input.Empty =>

              val nextI = i.fold {

                case Step.Cont(k) =>
                  val n = k(in)
                  n.fold {
                    case Step.Cont(kk) =>
                      p.success(Cont(kk))
                      Future.successful(Cont(step))
                    case _ => 
                      p.success(n)
                      Future.successful(Done((), Input.Empty: Input[EE]))
                  }
                case _ =>
                  p.success(i)
                  Future.successful(Done((),Input.Empty: Input[EE]))

              }
              Iteratee.flatten(nextI)
            case Input.EOF => {
              if(attending.single.transformAndGet { _.map(f) } == Some((false, false))){
                p.complete(Try(Iteratee.flatten(i.feed(Input.EOF))))
              } else {
                p.success(i)
              }
              Done((), Input.Empty)
            }
          }
        }
        Cont(step)
      }

      val itE1 = iteratee[E1] { case (l, r) => (false, r) }
      val itE2 = iteratee[E2] { case (l, r) => (l, false) }
      val r1 = e1 |>>| itE1
      val r2 = e2 |>>| itE2
      r1.flatMap(_ => r2).onComplete {
        case Success(_) => 
          redeemResultIfNotYet(iter.single())
        case Failure(e) => result.failure(e)

      }
      result.future
    }

  }

  trait Pushee[E] {

    def push(item: E): Boolean

    def close()

  }

  @scala.deprecated("use Concurrent.broadcast instead", "2.1.0")
  def imperative[E](
    onStart: () => Unit = () => (),
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()): PushEnumerator[E] = new PushEnumerator[E](onStart, onComplete, onError)


  @scala.deprecated("use Concurrent.unicast instead", "2.1.0")
  def pushee[E](
    onStart: Pushee[E] => Unit,
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()) = new Enumerator[E] {

    def apply[A](it: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = {
      var iteratee: Iteratee[E, A] = it
      var promise: scala.concurrent.Promise[Iteratee[E, A]] = Promise[Iteratee[E, A]]()

      val pushee = new Pushee[E] {
        def close() {
          if (iteratee != null) {
            iteratee.feed(Input.EOF).map(result => promise.success(result))
            iteratee = null
            promise = null
          }
        }
        def push(item: E): Boolean = {
          if (iteratee != null) {
            iteratee = iteratee.pureFlatFold[E, A] {

              case Step.Done(a, in) => {
                onComplete()
                Done(a, in)
              }

              case Step.Cont(k) => {
                val next = k(Input.El(item))
                next.pureFlatFold {
                  case Step.Done(a, in) => {
                    onComplete()
                    next
                  }
                  case _ => next
                }
              }

              case Step.Error(e, in) => {
                onError(e, in)
                Error(e, in)
              }
            }
            true
          } else {
            false
          }
        }
      }
      onStart(pushee)
      promise.future
    }

  }


  /**
   * Like [[org.http4s.iteratee.Enumerator.unfold]], but allows the unfolding to be done asynchronously.
   *
   * @param s The value to unfold
   * @param f The unfolding function. This will take the value, and return a future for some tuple of the next value
   *          to unfold and the next input, or none if the value is completely unfolded.
   */
  def unfoldM[S,E](s:S)(f: S => Future[Option[(S,E)]] ): Enumerator[E] = checkContinue1(s)(new TreatCont1[E,S]{

    def apply[A](loop: (Iteratee[E,A],S) => Future[Iteratee[E,A]], s:S, k: Input[E] => Iteratee[E,A])(implicit executor: ExecutionContext): Future[Iteratee[E,A]] = f(s).flatMap {
      case Some((newS,e)) => loop(k(Input.El(e)),newS)
      case None => Future.successful(Cont(k))
    }
  })

  /**
   * Unfold a value of type S into input for an enumerator.
   *
   * For example, the following would enumerate the elements of a list, implementing the same behavior as
   * Enumerator.enumerate:
   *
   * {{{
   *   Enumerator.sequence[List[Int], Int]{ list =>
   *     list.headOption.map(input => list.tail -> input)
   *   }
   * }}}
   *
   * @param s The value to unfold
   * @param f The unfolding function. This will take the value, and return some tuple of the next value to unfold and
   *          the next input, or none if the value is completely unfolded.
   */
  def unfold[S,E](s:S)(f: S => Option[(S,E)] ): Enumerator[E] = checkContinue1(s)(new TreatCont1[E,S]{

    def apply[A](loop: (Iteratee[E,A],S) => Future[Iteratee[E,A]], s:S, k: Input[E] => Iteratee[E,A]):Future[Iteratee[E,A]] = f(s) match {
      case Some((s,e)) => loop(k(Input.El(e)),s)
      case None => Future.successful(Cont(k))
    }
  })

  /**
   * Repeat the given input function indefinitely.
   *
   * @param e The input function.
   */
  def repeat[E](e: => E): Enumerator[E] = checkContinue0( new TreatCont0[E]{

    def apply[A](loop: Iteratee[E,A] => Future[Iteratee[E,A]], k: Input[E] => Iteratee[E,A]) = loop(k(Input.El(e)))

  })

  /**
   * Like [[org.http4s.iteratee.Enumerator.repeat]], but allows repeated values to be asynchronously fetched.
   *
   * @param e The input function
   */
  def repeatM[E](e: => Future[E]): Enumerator[E] = checkContinue0( new TreatCont0[E]{

    def apply[A](loop: Iteratee[E,A] => Future[Iteratee[E,A]], k: Input[E] => Iteratee[E,A])(implicit executor: ExecutionContext) = e.flatMap(ee => loop(k(Input.El(ee))))

  })

  /**
   * Like [[org.http4s.iteratee.Enumerator.repeatM]], but the callback returns an Option, which allows the stream
   * to be eventually terminated by returning None.
   *
   * @param e The input function.  Returns a future eventually redeemed with Some value if there is input to pass, or a
   *          future eventually redeemed with None if the end of the stream has been reached.
   */
  def generateM[E](e: => Future[Option[E]]): Enumerator[E] = checkContinue0( new TreatCont0[E] {

    def apply[A](loop: Iteratee[E,A] => Future[Iteratee[E,A]], k: Input[E] => Iteratee[E,A])(implicit executor: ExecutionContext) = e.flatMap {
      case Some(e) => loop(k(Input.El(e)))
      case None => Future.successful(Cont(k))
    }
  })

  trait TreatCont0[E]{

    def apply[A](loop: Iteratee[E,A] => Future[Iteratee[E,A]], k: Input[E] => Iteratee[E,A]):Future[Iteratee[E,A]]

  }

  def checkContinue0[E](inner:TreatCont0[E]) = new Enumerator[E] {

    def apply[A](it: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = {

      def step(it: Iteratee[E, A]): Future[Iteratee[E,A]] = it.fold {
          case Step.Done(a, e) => Future.successful(Done(a,e))
          case Step.Cont(k) => inner[A](step,k)
          case Step.Error(msg, e) => Future.successful(Error(msg,e))
      }

      step(it)
    }
  }

  trait TreatCont1[E,S]{

    def apply[A](loop: (Iteratee[E,A],S) => Future[Iteratee[E,A]], s:S, k: Input[E] => Iteratee[E,A]):Future[Iteratee[E,A]]

  }

  def checkContinue1[E,S](s:S)(inner:TreatCont1[E,S]) = new Enumerator[E] {

    def apply[A](it: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = {

      def step(it: Iteratee[E, A], state:S): Future[Iteratee[E,A]] = it.fold{
          case Step.Done(a, e) => Future.successful(Done(a,e))
          case Step.Cont(k) => inner[A](step,state,k)
          case Step.Error(msg, e) => Future.successful(Error(msg,e))
      }
      step(it,s)
    }

  }

  def fromCallback1[E](retriever: Boolean => Future[Option[E]],
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()) = new Enumerator[E] {
    def apply[A](it: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = {

      var iterateeP = Promise[Iteratee[E, A]]()

      def step(it: Iteratee[E, A], initial: Boolean = false) {

        val next = it.fold {
          case Step.Cont(k) => {
            retriever(initial).map {
              case None => {
                val remainingIteratee = k(Input.EOF)
                iterateeP.success(remainingIteratee)
                None
              }
              case Some(read) => {
                val nextIteratee = k(Input.El(read))
                Some(nextIteratee)
              }
            }
          }
          case _ => { iterateeP.success(it); Future.successful(None) }
        }

        next.onComplete {
          case Success(Some(i)) => step(i)

          case Success(None) => onComplete()
          case Failure(e) =>
            iterateeP.failure(e)
        }
      }
      step(it, true)
      iterateeP.future
    }
  }

  @scala.deprecated("use Enumerator.generateM instead", "2.1.0")
  def fromCallback[E](retriever: () => Future[Option[E]],
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()) = new Enumerator[E] {
    def apply[A](it: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = {

      var iterateeP = Promise[Iteratee[E, A]]()

      def step(it: Iteratee[E, A]) {

        val next = it.fold{
          case Step.Cont(k) => {
            retriever().map {
              case None => {
                val remainingIteratee = k(Input.EOF)
                iterateeP.success(remainingIteratee)
                None
              }
              case Some(read) => {
                val nextIteratee = k(Input.El(read))
                Some(nextIteratee)
              }
            }
          }
          case _ => { iterateeP.success(it); Future.successful(None) }
        }

        next.onComplete {
          case Success(Some(i)) => step(i)
          case Failure(e) =>
            iterateeP.failure(e)
          case _ => onComplete()
        }

      }

      step(it)
      iterateeP.future
    }
  }

  /**
   * Create an enumerator from the given input stream.
   *
   * This enumerator will block on reading the input stream, in the default iteratee thread pool.  Care must therefore
   * be taken to ensure that this isn't a slow stream.  If using this with slow input streams, consider setting the
   * value of iteratee-threadpool-size to a value appropriate for handling the blocking.
   *
   * @param input The input stream
   * @param chunkSize The size of chunks to read from the stream.
   */
  def fromStream(input: java.io.InputStream, chunkSize: Int = 1024 * 8) = {
    generateM({
      val buffer = new Array[Byte](chunkSize)
      val chunk = input.read(buffer) match {
        case -1 => None
        case read =>
          val input = new Array[Byte](read)
          System.arraycopy(buffer, 0, input, 0, read)
          Some(input)
      }
      Future.successful(chunk)}).onDoneEnumerating(input.close)
  }

  /**
   * Create an enumerator from the given input stream.
   *
   * Note that this enumerator will block when it reads from the file.
   *
   * @param file The file to create the enumerator from.
   * @param chunkSize The size of chunks to read from the file.
   */
  def fromFile(file: java.io.File, chunkSize: Int = 1024 * 8): Enumerator[Array[Byte]] = {
    fromStream(new java.io.FileInputStream(file), chunkSize)
  }

  /**
   * Create an Enumerator of bytes with an OutputStream.
   *
   * Not that calls to write will not block, so if the iteratee that is being fed to is slow to consume the input, the
   * OutputStream will not push back.  This means it should not be used with large streams since there is a risk of
   * running out of memory.
   *
   * @param a A callback that provides the output stream when this enumerator is written to an iteratee.
   */
  def outputStream(a: java.io.OutputStream => Unit): Enumerator[Array[Byte]] = {
    Concurrent.unicast[Array[Byte]] { channel =>
      val outputStream = new java.io.OutputStream(){
        override def close() {
          channel.end()
        }
        override def flush() {}
        override def write(value: Int) {
          channel.push(Array(value.toByte))
        }
        override def write(buffer: Array[Byte]) {
          write(buffer, 0, buffer.length)
        }
        override def write(buffer: Array[Byte], start: Int, count: Int) {
          channel.push(buffer.slice(start, start+count))
        }
      }
      a(outputStream)
    }
  }

  /**
   * An enumerator that produces EOF and nothing else.
   */
  def eof[A] = enumInput[A](Input.EOF)

  /**
   * Create an Enumerator from a set of values
   *
   * Example:
   * {{{
   *   val enumerator: Enumerator[String] = Enumerator("kiki", "foo", "bar")
   * }}}
   */
  def apply[E](in: E*): Enumerator[E] = new Enumerator[E] {

    def apply[A](i: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = enumerateSeq(executor)(in, i)

  }

  /**
   * Create an Enumerator from any TraversableOnce like collection of elements.
   *
   * Example of an iterator of lines of a file : 
   * {{{
   *  val enumerator: Enumerator[String] = Enumerator( scala.io.Source.fromFile("myfile.txt").getLines ) 
   * }}}
   */
  def enumerate[E](traversable : TraversableOnce[E])(implicit ctx:scala.concurrent.ExecutionContext): Enumerator[E]  = {
    val it = traversable.toIterator
    Enumerator.unfoldM[scala.collection.Iterator[E], E](it: scala.collection.Iterator[E] )({ currentIt => 
      if(currentIt.hasNext)
        Future[ Option[(scala.collection.Iterator[E], E)] ]({
          val next = currentIt.next
          Some( (currentIt -> next) )
        })(ctx)
      else 
        Future.successful[ Option[(scala.collection.Iterator[E], E)] ]({
          None
        })
    })
  }

  private def enumerateSeq[E, A](implicit executor: ExecutionContext): (Seq[E], Iteratee[E, A]) => Future[Iteratee[E, A]] = { (l, i) =>
    l.foldLeft(Future.successful(i))((i, e) =>
      i.flatMap(it => it.pureFold{ 
        case Step.Cont(k) => k(Input.El(e))
        case _ => it
      }))
  }

  private[iteratee] def enumerateSeq1[E](s:Seq[E]):Enumerator[E] = checkContinue1(s)(new TreatCont1[E,Seq[E]]{
    def apply[A](loop: (Iteratee[E,A],Seq[E]) => Future[Iteratee[E,A]], s:Seq[E], k: Input[E] => Iteratee[E,A]):Future[Iteratee[E,A]] =
      if(!s.isEmpty)
        loop(k(Input.El(s.head)),s.tail)
      else Future.successful(Cont(k))
  })

  private[iteratee] def enumerateSeq2[E](s:Seq[Input[E]]):Enumerator[E] = checkContinue1(s)(new TreatCont1[E,Seq[Input[E]]]{
    def apply[A](loop: (Iteratee[E,A],Seq[Input[E]]) => Future[Iteratee[E,A]], s:Seq[Input[E]], k: Input[E] => Iteratee[E,A]):Future[Iteratee[E,A]] =
      if(!s.isEmpty)
        loop(k(s.head),s.tail)
      else Future.successful(Cont(k))
  })

}

@scala.deprecated("use Concurrent.broadcast instead", "2.1.0")
class PushEnumerator[E] private[iteratee] (
    onStart: () => Unit = () => (),
    onComplete: () => Unit = () => (),
    onError: (String, Input[E]) => Unit = (_: String, _: Input[E]) => ()) extends Enumerator[E] with Enumerator.Pushee[E] {

  var iteratee: Iteratee[E, _] = _
  var promise: Promise[Iteratee[E, _]]= _

  def apply[A](it: Iteratee[E, A])(implicit executor: ExecutionContext): Future[Iteratee[E, A]] = {
    onStart()
    iteratee = it.asInstanceOf[Iteratee[E, _]]
    val newPromise = Promise[Iteratee[E, A]]()
    promise = newPromise.asInstanceOf[Promise[Iteratee[E, _]]]
    newPromise.future
  }

  def close()(implicit executor: ExecutionContext) {
    if (iteratee != null) {
      iteratee.feed(Input.EOF).map(result => promise.success(result))
      iteratee = null
      promise = null
    }
  }

  def push(item: E)(implicit executor: ExecutionContext): Boolean = {
    if (iteratee != null) {
      iteratee = iteratee.pureFlatFold[E, Any] {

        case Step.Done(a, in) => {
          onComplete()
          Done(a, in)
        }

        case Step.Cont(k) => {
          val next = k(Input.El(item))
          next.pureFlatFold {
            case Step.Done(a, in) => {
              onComplete()
              next
            }
            case _ => next
          }
        }

        case Step.Error(e, in) => {
          onError(e, in)
          Error(e, in)
        }
      }
      true
    } else {
      false
    }
  }

}