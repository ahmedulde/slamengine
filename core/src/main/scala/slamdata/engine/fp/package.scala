package slamdata.engine

import collection.immutable.ListMap

import scalaz._
import Scalaz._
import Liskov._
import scalaz.concurrent.{Task}

sealed trait LowerPriorityTreeInstances {
  implicit def Tuple2RenderTree[A, B](implicit RA: RenderTree[A], RB: RenderTree[B]) =
    new RenderTree[(A, B)] {
      override def render(t: (A, B)) =
        NonTerminal("tuple" :: Nil, None,
          RA.render(t._1) ::
            RB.render(t._2) ::
            Nil)
    }
}

sealed trait LowPriorityTreeInstances extends LowerPriorityTreeInstances {
  implicit def LeftTuple3RenderTree[A, B, C](implicit RA: RenderTree[A], RB: RenderTree[B], RC: RenderTree[C]) =
    new RenderTree[((A, B), C)] {
      override def render(t: ((A, B), C)) =
        NonTerminal("tuple" :: Nil, None,
          RA.render(t._1._1) ::
            RB.render(t._1._2) ::
            RC.render(t._2) ::
            Nil)
    }
}

sealed trait TreeInstances extends LowPriorityTreeInstances {
  implicit def LeftTuple4RenderTree[A, B, C, D](implicit RA: RenderTree[A], RB: RenderTree[B], RC: RenderTree[C], RD: RenderTree[D]) =
    new RenderTree[(((A, B), C), D)] {
      override def render(t: (((A, B), C), D)) =
        NonTerminal("tuple" :: Nil, None,
           RA.render(t._1._1._1) ::
            RB.render(t._1._1._2) ::
            RC.render(t._1._2) ::
            RD.render(t._2) ::
            Nil)
    }

  implicit def EitherRenderTree[A, B](implicit RA: RenderTree[A], RB: RenderTree[B]) =
    new RenderTree[A \/ B] {
      override def render(v: A \/ B) =
        v match {
          case -\/ (a) => NonTerminal("-\\/" :: Nil, None, RA.render(a) :: Nil)
          case \/- (b) => NonTerminal("\\/-" :: Nil, None, RB.render(b) :: Nil)
        }
    }

  implicit def ScalaEitherRenderTree[A, B](implicit RA: RenderTree[A], RB: RenderTree[B]) =
    new RenderTree[Either[A, B]] {
      override def render(v: Either[A, B]) =
        v match {
          case Left(a) => NonTerminal(List("Left"), None, RA.render(a) :: Nil)
          case Right(b) => NonTerminal(List("Right"), None, RB.render(b) :: Nil)
        }
    }

  implicit def OptionRenderTree[A](implicit RA: RenderTree[A]) =
    new RenderTree[Option[A]] {
      override def render(o: Option[A]) = o match {
        case Some(a) => RA.render(a)
        case None => Terminal("None" :: "Option" :: Nil, None)
      }
    }

  implicit def ListRenderTree[A](implicit RA: RenderTree[A]) =
    new RenderTree[List[A]] {
      def render(v: List[A]) = NonTerminal(List("List"), None, v.map(RA.render))
    }

  implicit def ListMapRenderTree[K, V](implicit RV: RenderTree[V]) =
    new RenderTree[ListMap[K, V]] {
      def render(v: ListMap[K, V]) =
        NonTerminal("Map" :: Nil, None,
          v.toList.map { case (k, v) =>
            NonTerminal("Key" :: "Map" :: Nil, Some(k.toString), RV.render(v) :: Nil)
          })
    }

  implicit def RenderTreeToShow[N: RenderTree] = new Show[N] {
    override def show(v: N) = RenderTree.show(v)
  }
}

sealed trait ListMapInstances {
  implicit def seqW[A](xs: Seq[A]) = new SeqW(xs)
  class SeqW[A](xs: Seq[A]) {
    def toListMap[B, C](implicit ev: A <~< (B, C)): ListMap[B, C] = {
      ListMap(co[Seq, A, (B, C)](ev)(xs) : _*)
    }
  }

  implicit def TraverseListMap[K] = new Traverse[ListMap[K, ?]] with IsEmpty[ListMap[K, ?]] {
    def empty[V] = ListMap.empty[K, V]
    def plus[V](a: ListMap[K, V], b: => ListMap[K, V]) = a ++ b
    def isEmpty[V](fa: ListMap[K, V]) = fa.isEmpty
    override def map[A, B](fa: ListMap[K, A])(f: A => B) = fa.map{case (k, v) => (k, f(v))}
    def traverseImpl[G[_],A,B](m: ListMap[K,A])(f: A => G[B])(implicit G: Applicative[G]): G[ListMap[K,B]] = {
      import G.functorSyntax._
      scalaz.std.list.listInstance.traverseImpl(m.toList)({ case (k, v) => f(v) map (k -> _) }) map (_.toListMap)
    }
  }
}

trait TaskOps[A] extends scalaz.syntax.Ops[Task[A]] {
  import SKI._

  /**
   A new task which runs a cleanup task only in the case of failure, and ignores any result
   from the cleanup task.
   */
  final def onFailure(cleanup: Task[_]): Task[A] =
    self.attempt.flatMap(_.fold(
      err => cleanup.attempt.flatMap(κ(Task.fail(err))),
      Task.now
    ))

  /**
   A new task that ignores the result of this task, and runs another task no matter what.
  */
  final def ignoreAndThen[B](t: Task[B]): Task[B] =
    self.attempt.flatMap(κ(t))
}

trait ToTaskOps {
  implicit def ToTaskOpsFromTask[A](a: Task[A]): TaskOps[A] = new TaskOps[A] {
    val self = a
  }
}

trait PartialFunctionOps {
  implicit class PFOps[A, B](self: PartialFunction[A, B]) {
    def |?| [C](that: PartialFunction[A, C]): PartialFunction[A, B \/ C] =
      Function.unlift(v =>
        self.lift(v).fold[Option[B \/ C]](
          that.lift(v).map(\/-(_)))(
          x => Some(-\/(x))))
  }
}

trait JsonOps {
  import argonaut._
  import SKI._

  def optional[A: DecodeJson](cur: ACursor): DecodeResult[Option[A]] =
    cur.either.fold(
      κ(DecodeResult(\/- (None))),
      v => v.as[A].map(Some(_)))

  def orElse[A: DecodeJson](cur: ACursor, default: => A): DecodeResult[A] =
    cur.either.fold(
      κ(DecodeResult(\/- (default))),
      v => v.as[A]
    )

  def decodeJson[A](text: String)(implicit DA: DecodeJson[A]): String \/ A = for {
    json <- Parse.parse(text)
    a <- DA.decode(json.hcursor).result.leftMap { case (exp, hist) => "expected: " + exp + "; " + hist }
  } yield a


  /* Nicely formatted, order-preserving, single-line. */
  val minspace = PrettyParams(
    "",       // indent
    "", " ",  // lbrace
    " ", "",  // rbrace
    "", " ",  // lbracket
    " ", "",  // rbracket
    "",       // lrbracketsEmpty
    "", " ",  // arrayComma
    "", " ",  // objectComma
    "", " ",  // colon
    true,     // preserveOrder
    false     // dropNullKeys
  )

  /** Nicely formatted, order-preserving, 2-space indented. */
  val multiline = PrettyParams(
    "  ",     // indent
    "", "\n",  // lbrace
    "\n", "",  // rbrace
    "", "\n",  // lbracket
    "\n", "",  // rbracket
    "",       // lrbracketsEmpty
    "", "\n",  // arrayComma
    "", "\n",  // objectComma
    "", " ",  // colon
    true,     // preserveOrder
    false     // dropNullKeys
  )
}

trait ProcessOps {
  import scalaz.stream.Process

  implicit class PrOps[O](self: Process[Task, O]) {
    def cleanUpWith(t: Task[Unit]): Process[Task, O] = self.onComplete(Process.eval(t).drain)
  }
}

trait SKI {
  // NB: Unicode has double-struck and bold versions of the letters, which might
  //     be more appropriate, but the code points are larger than 2 bytes, so
  //     Scala doesn't handle them.

  /** Probably not useful; implemented here mostly because it's amusing. */
  def σ[A, B, C](x: A => B => C, y: A => B, z: A): C = x(z)(y(z))

  /**
   A shorter name for the constant function of 1, 2, 3, or 6 args.
   NB: the argument is eager here, so use `_ => ...` instead if you need it to be thunked.
   */
  def κ[A, B](x: B): A => B                                 = _ => x
  def κ[A, B, C](x: C): (A, B) => C                         = (_, _) => x
  def κ[A, B, C, D](x: D): (A, B, C) => D                   = (_, _, _) => x
  def κ[A, B, C, D, E, F, G](x: G): (A, B, C, D, E, F) => G = (_, _, _, _, _, _) => x

  /** A shorter name for the identity function. */
  def ɩ[A]: A => A = Predef.identity
}
object SKI extends SKI

package object fp extends TreeInstances with ListMapInstances with ToTaskOps with PartialFunctionOps with JsonOps with ProcessOps with SKI {
  sealed trait Polymorphic[F[_], TC[_]] {
    def apply[A: TC]: TC[F[A]]
  }

  trait ShowF[F[_]] {
    def show[A](fa: F[A])(implicit sa: Show[A]): Cord
  }

  implicit def ShowShowF[F[_], A: Show, FF[A] <: F[A]](implicit FS: ShowF[F]):
      Show[FF[A]] =
    new Show[FF[A]] { override def show(fa: FF[A]) = FS.show(fa) }

  implicit def ShowFNT[F[_]](implicit SF: ShowF[F]):
      Show ~> λ[α => Show[F[α]]] =
    new (Show ~> λ[α => Show[F[α]]]) {
      def apply[α](st: Show[α]): Show[F[α]] = ShowShowF(st, SF)
    }

  trait EqualF[F[_]] {
    def equal[A](fa1: F[A], fa2: F[A])(implicit eq: Equal[A]): Boolean
  }

  implicit def EqualEqualF[F[_], A: Equal, FF[A] <: F[A]](implicit FE: EqualF[F]):
      Equal[FF[A]] =
    new Equal[FF[A]] { def equal(fa1: FF[A], fa2: FF[A]) = FE.equal(fa1, fa2) }

  implicit def EqualFNT[F[_]](implicit EF: EqualF[F]):
      Equal ~> λ[α => Equal[F[α]]] =
    new (Equal ~> λ[α => Equal[F[α]]]) {
      def apply[α](eq: Equal[α]): Equal[F[α]] = EqualEqualF(eq, EF)
    }

  trait MonoidF[F[_]] {
    def append[A: Monoid](fa1: F[A], fa2: F[A]): F[A]
  }

  trait Empty[F[_]] {
    def empty[A]: F[A]
  }
  object Empty {
    def apply[F[+_]](value: F[Nothing]): Empty[F] = new Empty[F] {
      def empty[A]: F[A] = value
    }
  }

  implicit val SymbolEqual: Equal[Symbol] = new Equal[Symbol] {
    def equal(v1: Symbol, v2: Symbol): Boolean = v1 == v2
  }

  implicit val SymbolOrder: Order[Symbol] = new Order[Symbol] {
    def order(x: Symbol, y: Symbol): Ordering = Order[String].order(x.name, y.name)
  }

  implicit class ListOps[A](c: List[A]) {
    def decon = c.headOption map ((_, c.drop(1)))

    def tailOption = c.headOption map κ(c.drop(1))
  }

  def unzipDisj[A, B](ds: List[A \/ B]): (List[A], List[B]) = {
    val (as, bs) = ds.foldLeft((List[A](), List[B]())) {
      case ((as, bs), -\/ (a)) => (a :: as, bs)
      case ((as, bs),  \/-(b)) => (as, b :: bs)
    }
    (as.reverse, bs.reverse)
  }

  def parseInt(str: String): Option[Int] =
    \/.fromTryCatchNonFatal(str.toInt).toOption

  def parseBigInt(str: String): Option[BigInt] =
    \/.fromTryCatchNonFatal(BigInt(str)).toOption

  def parseDouble(str: String): Option[Double] =
    \/.fromTryCatchNonFatal(str.toDouble).toOption

  def parseBigDecimal(str: String): Option[BigDecimal] =
    \/.fromTryCatchNonFatal(BigDecimal(str)).toOption

  /**
   Accept a value (forcing the argument expression to be evaluated for its effects),
   and then discard it, returning Unit. Makes it explicit that you're discarding the
   result, and effectively suppresses the "NonUnitStatement" warning from wartremover.
   */
  def ignore[A](a: A): Unit = ()
}
