package zio.test

import zio.Exit.{Failure, Success}
import zio.stream.ZStream
import zio.test.Assertion.{equalTo, forall}
import zio.{Exit, UIO, URIO, ZIO}

object GenUtils {

  def alwaysShrinksTo[R, A](gen: Gen[R, A])(a: A): URIO[R, TestResult] = {
    val shrinks = if (TestPlatform.isJS) 1 else 100
    ZIO.collectAll(List.fill(shrinks)(shrinksTo(gen))).map(assert(_)(forall(equalTo(a))))
  }

  def checkFinite[A, B](
    gen: Gen[Any, A]
  )(assertion: Assertion[B], f: List[A] => B = (a: List[A]) => a): UIO[TestResult] =
    assertZIO(gen.sample.map(_.value).runCollect.map(xs => f(xs.toList)))(assertion)

  def checkSample[A, B](
    gen: Gen[Any, A],
    size: Int = 100
  )(assertion: Assertion[B], f: List[A] => B = (a: List[A]) => a): UIO[TestResult] =
    assertZIO(provideSize(sample100(gen).map(f))(size))(assertion)

  def checkShrink[A](gen: Gen[Any, A])(a: A): UIO[TestResult] =
    provideSize(alwaysShrinksTo(gen)(a: A))(100)

  val deterministic: Gen[Any, Gen[Any, Int]] =
    Gen.listOf1(Gen.int(-10, 10)).map(as => Gen.fromIterable(as))

  def equal[A](left: Gen[Any, A], right: Gen[Any, A]): UIO[Boolean] =
    equalSample(left, right).zipWith(equalShrink(left, right))(_ && _)

  def equalShrink[A](left: Gen[Any, A], right: Gen[Any, A]): UIO[Boolean] = {
    val testRandom = TestRandom.deterministic
    for {
      leftShrinks  <- ZIO.collectAll(List.fill(100)(shrinks(left))).provideLayer(testRandom)
      rightShrinks <- ZIO.collectAll(List.fill(100)(shrinks(right))).provideLayer(testRandom)
    } yield leftShrinks == rightShrinks
  }

  def equalSample[A](left: Gen[Any, A], right: Gen[Any, A]): UIO[Boolean] = {
    val testRandom = TestRandom.deterministic
    for {
      leftSample  <- sample100(left).provideLayer(testRandom)
      rightSample <- sample100(right).provideLayer(testRandom)
    } yield leftSample == rightSample
  }

  val genIntList: Gen[Any, List[Int]] = Gen.oneOf(
    Gen.const(List.empty),
    for {
      tail <- Gen.suspend(genIntList)
      head <- Gen.int(-10, 10)
    } yield head :: tail
  )

  val genStringIntFn: Gen[Any, String => Int] = Gen.function(Gen.int(-10, 10))

  def partitionExit[E, A](eas: List[Exit[E, A]]): (Iterable[Failure[E]], Iterable[A]) =
    ZIO.partitionMap(eas) {
      case Success(a)     => Right(a)
      case e @ Failure(_) => Left(e)
    }

  def provideSize[R, A](zio: ZIO[R, Nothing, A])(n: Int): URIO[R, A] =
    zio.provideSomeLayer[R](Sized.live(n))

  val random: Gen[Any, Gen[Any, Int]] =
    Gen.const(Gen.int(-10, 10))

  def shrinks[R, A](gen: Gen[R, A]): ZIO[R, Nothing, List[A]] =
    gen.sample.forever.take(1).flatMap(_.shrinkSearch(_ => true)).take(1000).runCollect.map(_.toList)

  def shrinksTo[R, A](gen: Gen[R, A]): URIO[R, A] =
    shrinks(gen).map(_.reverse.head)

  val smallInt: Gen[Any, Int] = Gen.int(-10, 10)

  def sample[R, A](gen: Gen[R, A]): ZIO[R, Nothing, List[A]] =
    gen.sample.map(_.value).runCollect.map(_.toList)

  def sample100[R, A](gen: Gen[R, A]): ZIO[R, Nothing, List[A]] =
    gen.sample.map(_.value).forever.take(100).runCollect.map(_.toList)

  def sampleEffect[E, A](
    gen: Gen[Any, ZIO[Any, E, A]],
    size: Int = 100
  ): ZIO[Any, Nothing, List[Exit[E, A]]] =
    provideSize(sample100(gen).flatMap(effects => ZIO.foreach(effects)(_.exit)))(size)

  def shrink[R, A](gen: Gen[R, A]): URIO[R, A] =
    gen.sample.take(1).flatMap(_.shrinkSearch(_ => true)).take(1000).runLast.map(_.get)

  val shrinkable: Gen[Any, Int] =
    Gen.fromRandomSample(_.nextIntBounded(90).map(_ + 10).map(Sample.shrinkIntegral(0)))

  def shrinkWith[R, A](gen: Gen[R, A])(f: A => Boolean): ZIO[R, Nothing, List[A]] =
    gen.sample.take(1).flatMap(_.shrinkSearch(!f(_))).take(1000).filter(!f(_)).runCollect.map(_.toList)

  val three: Gen[Any, Int] = Gen(ZStream(Sample.unfold[Any, Int, Int](3) { n =>
    if (n == 0) (n, ZStream.empty)
    else (n, ZStream(n - 1))
  }))

  implicit class ExistsFaster[A](val l: List[A]) {

    /**
     * Use optimized List iterator StrictOptimizedLinearSeqOps exists, instead
     * of List.exists.
     */
    def existsFast(p: A => Boolean): Boolean = l.iterator.exists(e => p(e))
  }

}
