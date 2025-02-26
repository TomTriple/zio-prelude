package zio.prelude.fx

import zio.prelude._
import zio.prelude.laws._
import zio.test.Assertion.{equalTo => _, _}
import zio.test._
import zio.{CanFail, Chunk, NonEmptyChunk, ZEnvironment, ZIO}

import java.util.NoSuchElementException
import scala.util.Try

object ZPureSpec extends ZIOSpecDefault {

  lazy val genInt: Gen[Any, Int] =
    Gen.int

  lazy val genString: Gen[Sized, String] =
    Gen.string

  lazy val genIntIntToInt: Gen[Any, (Int, Int) => Int] =
    Gen.function2(genInt)

  lazy val genIntToInt: Gen[Any, Int => Int] =
    Gen.function(genInt)

  lazy val genIntToIntInt: Gen[Any, Int => (Int, Int)] =
    Gen.function(genInt <*> genInt)

  lazy val genIntToState: Gen[Any, Int => State[Int, Int]] =
    Gen.function(genState)

  lazy val genState: Gen[Any, State[Int, Int]] =
    Gens.state(genInt, genInt)

  lazy val genStateState: Gen[Any, State[Int, State[Int, Int]]] =
    Gens.state(genInt, genState)

  def spec: Spec[Environment, Any] =
    suite("ZPureSpec")(
      suite("context")(
        suite("constructors")(
          test("access") {
            check(genIntToInt, genInt, genInt) { (f, r, s) =>
              val actual   = ZPure.serviceWith(f).provideEnvironment(ZEnvironment(r)).run(s)
              val expected = (s, f(r))
              assert(actual)(equalTo(expected))
            }
          },
          test("accessM") {
            val zPure = ZPure.environmentWithPure[Int](n => State.update[Int, Int](_ + n.get))
            assert(zPure.provideEnvironment(ZEnvironment(2)).runState(3))(equalTo(5))
          },
          test("provide is scoped correctly") {
            val zPure = for {
              start <- ZPure.service[Any, Int]
              inner <- (for {
                         innerStart <- ZPure.service[Any, Int]
                         innerInner <- ZPure.service[Any, Int].provideEnvironment(ZEnvironment(111))
                         innerEnd   <- ZPure.service[Any, Int]
                       } yield (innerStart, innerInner, innerEnd)).provideEnvironment(ZEnvironment(11))
              end   <- ZPure.service[Any, Int]
            } yield (start, inner, end)
            assert(zPure.provideEnvironment(ZEnvironment(1)).run)(equalTo((1, (11, 111, 11), 1)))
          },
          test("provided environment should be restored on error") {
            val zPure = for {
              _   <- (ZPure
                       .fail(()): ZPure[Nothing, Any, Any, Int, Unit, Nothing]).provideEnvironment(ZEnvironment(1)).either
              end <- ZPure.service[Any, Int]
            } yield end
            assert(zPure.provideEnvironment(ZEnvironment(0)).run)(equalTo(0))
          },
          test("providing environment should preserve errors") {
            val zPure: ZPure[Nothing, Unit, Unit, (Int, Int), Int, Int] =
              ZPure.fail(1).zipPar(ZPure.fail(2)).as(0)
            val actual                                                  = zPure.provideEnvironment(ZEnvironment((1, 2))).runValidation
            val expected                                                = Validation.Failure(Chunk.empty, NonEmptyChunk(1, 2))
            assert(actual)(equalTo(expected))
          },
          test("provideSome") {
            val zPure =
              ZPure.service[Any, Int].provideSomeEnvironment[String](env => ZEnvironment(env.get.split(" ").length))
            assert(zPure.provideEnvironment(ZEnvironment("The quick brown fox")).run)(equalTo(4))
          }
        )
      ),
      suite("state")(
        suite("methods")(
          test("contramap") {
            check(genState, genIntToInt, genInt) { (fa, f, s) =>
              val (s1, a1) = fa.run(s)
              assert(fa.mapState(f).run(s))(equalTo((f(s1), a1)))
            }
          },
          test("filterOrElse") {
            check(genInt, genInt, genInt, genInt, genInt) { (s1, s2, s3, a1, a2) =>
              val z = ZPure.succeed[Int, Int](a1).asState(s2)
              val f = (_: Int) => ZPure.succeed(a2).asState(s3)
              assert(z.filterOrElse(_ => true)(f).run(s1))(equalTo((s2, a1))) &&
              assert(z.filterOrElse(_ => false)(f).run(s1))(equalTo((s3, a2)))
            }
          },
          test("filterOrElse_") {
            check(genInt, genInt, genInt, genInt, genInt) { (s1, s2, s3, a1, a2) =>
              val z1 = ZPure.succeed[Int, Int](a1).asState(s2)
              val z2 = ZPure.succeed(a2).asState(s3)
              assert(z1.filterOrElse_(_ => true)(z2).run(s1))(equalTo((s2, a1))) &&
              assert(z1.filterOrElse_(_ => false)(z2).run(s1))(equalTo((s3, a2)))
            }
          },
          test("filterOrFail") {
            check(genInt, genInt) { (a, e) =>
              val z = ZPure.succeed[Unit, Int](a)
              assert(z.filterOrFail(_ => true)(e).getState.either.run)(isRight(equalTo(((), a)))) &&
              assert(z.filterOrFail(_ => false)(e).getState.either.run)(isLeft(equalTo(e)))
            }
          },
          test("flatMap") {
            check(genState, genIntToState, genInt) { (fa, f, s) =>
              val (s1, a1) = fa.run(s)
              val (s2, a2) = f(a1).run(s1)
              assert(fa.flatMap(f).run(s))(equalTo((s2, a2)))
            }
          },
          test("flatten") {
            check(genStateState, genInt) { (ffa, s) =>
              val (s1, fa) = ffa.run(s)
              val (s2, b)  = fa.run(s1)
              assert(ffa.flatten.run(s))(equalTo((s2, b)))
            }
          },
          test("head") {
            check(genInt, genString, genString) { (s, el, el2) =>
              val optOrHead = ZPure.succeed[Int, List[String]](List(el, el2)).head.getState.either.runResult(s)
              assert(optOrHead)(isRight(equalTo((s, el))))
            }
          },
          test("head (Failure case)") {
            check(genInt, genString, genString) { (s, e, el) =>
              val optOrHead = ZPure.fail(e).as(List(el)).head.getState.either.runResult(s)
              assert(optOrHead)(isLeft(equalTo(Option(e))))
            }
          },
          test("head (empty List)") {
            check(genInt) { s =>
              val optOrHead = ZPure.succeed[Int, List[String]](List.empty).head.getState.either.runResult(s)
              assert(optOrHead)(isLeft(equalTo(Option.empty[String])))
            }
          },
          test("map") {
            check(genState, genIntToInt, genInt) { (fa, f, s) =>
              val (s1, a1) = fa.run(s)
              assert(fa.map(f).run(s))(equalTo((s1, f(a1))))
            }
          },
          test("mapState") {
            check(genState, genIntToInt, genInt) { (fa, f, s) =>
              val (s1, a1) = fa.run(s)
              assert(fa.mapState(f).run(s))(equalTo((f(s1), a1)))
            }
          },
          test("negate") {
            check(genInt) { s =>
              assert(State.succeed[Int, Boolean](true).negate.run(s))(equalTo((s, false))) &&
              assert(State.succeed[Int, Boolean](false).negate.run(s))(equalTo((s, true)))
            }
          },
          suite("repeatN")(
            test("success") {
              val f = (s: Int) => ((s + 1) * 10, s + 1)
              assert(State.modify(f).repeatN(0).run(0))(equalTo((1, 10))) &&
              assert(State.modify(f).repeatN(1).run(0))(equalTo((2, 20))) &&
              assert(State.modify(f).repeatN(2).run(0))(equalTo((3, 30))) &&
              assert(State.modify(f).repeatN(3).run(0))(equalTo((4, 40))) &&
              assert(State.modify(f).repeatN(4).run(0))(equalTo((5, 50)))
            },
            test("failure") {
              val f = (s: Int) => if (s == 3) Left("error") else Right(((s + 1) * 10, s + 1))
              assert(ZPure.modifyEither(f).repeatN(5).getState.either.runResult(0))(isLeft(equalTo("error")))
            }
          ),
          suite("repeatUntil")(
            test("success") {
              val f = (s: Int) => ((s + 1) / 10, s + 1)
              assert(State.modify(f).repeatUntil(_ == 0).run(0))(equalTo((1, 0))) &&
              assert(State.modify(f).repeatUntil(_ == 1).run(0))(equalTo((10, 1))) &&
              assert(State.modify(f).repeatUntil(_ == 2).run(0))(equalTo((20, 2))) &&
              assert(State.modify(f).repeatUntil(_ == 3).run(0))(equalTo((30, 3))) &&
              assert(State.modify(f).repeatUntil(_ == 4).run(0))(equalTo((40, 4)))
            },
            test("failure") {
              val f = (s: Int) => if (s == 3) Left("error") else Right(((s + 1) / 10, s + 1))
              assert(ZPure.modifyEither(f).repeatUntil(_ == 1).getState.either.runResult(0))(isLeft(equalTo("error")))
            }
          ),
          suite("repeatUntilEquals")(
            test("success") {
              val f = (s: Int) => ((s + 1) / 10, s + 1)
              assert(State.modify(f).repeatUntilEquals(0).run(0))(equalTo((1, 0))) &&
              assert(State.modify(f).repeatUntilEquals(1).run(0))(equalTo((10, 1))) &&
              assert(State.modify(f).repeatUntilEquals(2).run(0))(equalTo((20, 2))) &&
              assert(State.modify(f).repeatUntilEquals(3).run(0))(equalTo((30, 3))) &&
              assert(State.modify(f).repeatUntilEquals(4).run(0))(equalTo((40, 4)))
            },
            test("failure") {
              val f = (s: Int) => if (s == 3) Left("error") else Right(((s + 1) / 10, s + 1))
              assert(ZPure.modifyEither(f).repeatUntilEquals(1).getState.either.runResult(0))(isLeft(equalTo("error")))
            }
          ),
          suite("repeatUntilState")(
            test("success") {
              val f = (s: Int) => ((s + 1) / 10, s + 1)
              assert(State.modify(f).repeatUntilState(_ == 1).run(0))(equalTo((1, 0))) &&
              assert(State.modify(f).repeatUntilState(_ == 10).run(0))(equalTo((10, 1))) &&
              assert(State.modify(f).repeatUntilState(_ == 20).run(0))(equalTo((20, 2))) &&
              assert(State.modify(f).repeatUntilState(_ == 30).run(0))(equalTo((30, 3))) &&
              assert(State.modify(f).repeatUntilState(_ == 40).run(0))(equalTo((40, 4)))
            },
            test("failure") {
              val f = (s: Int) => if (s == 3) Left("error") else Right(((s + 1) / 10, s + 1))
              assert(ZPure.modifyEither(f).repeatUntilState(_ == 10).getState.either.runResult(0))(
                isLeft(equalTo("error"))
              )
            }
          ),
          suite("repeatUntilStateEquals")(
            test("success") {
              val f = (s: Int) => ((s + 1) / 10, s + 1)
              assert(State.modify(f).repeatUntilStateEquals(1).run(0))(equalTo((1, 0))) &&
              assert(State.modify(f).repeatUntilStateEquals(10).run(0))(equalTo((10, 1))) &&
              assert(State.modify(f).repeatUntilStateEquals(20).run(0))(equalTo((20, 2))) &&
              assert(State.modify(f).repeatUntilStateEquals(30).run(0))(equalTo((30, 3))) &&
              assert(State.modify(f).repeatUntilStateEquals(40).run(0))(equalTo((40, 4)))
            },
            test("failure") {
              val f = (s: Int) => if (s == 3) Left("error") else Right(((s + 1) / 10, s + 1))
              assert(ZPure.modifyEither(f).repeatUntilStateEquals(10).getState.either.runResult(0))(
                isLeft(equalTo("error"))
              )
            }
          ),
          suite("repeatWhile")(
            test("success") {
              val f = (s: Int) => ((s + 1) / 10, s + 1)
              assert(State.modify(f).repeatWhile(_ < 0).run(0))(equalTo((1, 0))) &&
              assert(State.modify(f).repeatWhile(_ < 1).run(0))(equalTo((10, 1))) &&
              assert(State.modify(f).repeatWhile(_ < 2).run(0))(equalTo((20, 2))) &&
              assert(State.modify(f).repeatWhile(_ < 3).run(0))(equalTo((30, 3))) &&
              assert(State.modify(f).repeatWhile(_ < 4).run(0))(equalTo((40, 4)))
            },
            test("failure") {
              val f = (s: Int) => if (s == 3) Left("error") else Right(((s + 1) / 10, s + 1))
              assert(ZPure.modifyEither(f).repeatWhile(_ < 1).getState.either.runResult(0))(isLeft(equalTo("error")))
            }
          ),
          suite("repeatWhileEquals")(
            test("success") {
              val f = (s: Int) => ((s + 1) / 10, s + 1)
              assert(State.modify(f).repeatWhileEquals(0).run(0))(equalTo((10, 1)))
            },
            test("failure") {
              val f = (s: Int) => if (s == 3) Left("error") else Right(((s + 1) / 10, s + 1))
              assert(ZPure.modifyEither(f).repeatWhileEquals(0).getState.either.runResult(0))(isLeft(equalTo("error")))
            }
          ),
          suite("repeatWhileState")(
            test("success") {
              val f = (s: Int) => ((s + 1) / 10, s + 1)
              assert(State.modify(f).repeatWhileState(_ < 1).run(0))(equalTo((1, 0))) &&
              assert(State.modify(f).repeatWhileState(_ < 10).run(0))(equalTo((10, 1))) &&
              assert(State.modify(f).repeatWhileState(_ < 20).run(0))(equalTo((20, 2))) &&
              assert(State.modify(f).repeatWhileState(_ < 30).run(0))(equalTo((30, 3))) &&
              assert(State.modify(f).repeatWhileState(_ < 40).run(0))(equalTo((40, 4)))
            },
            test("failure") {
              val f = (s: Int) => if (s == 3) Left("error") else Right(((s + 1) / 10, s + 1))
              assert(ZPure.modifyEither(f).repeatWhileState(_ < 10).getState.either.runResult(0))(
                isLeft(equalTo("error"))
              )
            }
          ),
          test("run") {
            check(genIntToIntInt, genInt) { (f, s) =>
              assert(State.modify(f).run(s))(equalTo(f(s).swap))
            }
          },
          test("runResult") {
            check(genIntToIntInt, genInt) { (f, s) =>
              assert(State.modify(f).runResult(s))(equalTo(f(s)._1))
            }
          },
          test("runState") {
            check(genIntToIntInt, genInt) { (f, s) =>
              assert(State.modify(f).runState(s))(equalTo(f(s)._2))
            }
          },
          test("unit") {
            check(genInt, genInt) { (s, a) =>
              assert(State.succeed[Int, Int](a).unit.run(s))(equalTo((s, ())))
            }
          },
          test("zip") {
            check(genState, genState, genInt) { (fa, fb, s) =>
              val (s1, a) = fa.run(s)
              val (s2, b) = fb.run(s1)
              assert(fa.zip(fb).run(s))(equalTo((s2, (a, b))))
            }
          },
          test("zipLeft") {
            check(genState, genState, genInt) { (fa, fb, s) =>
              val (s1, a) = fa.run(s)
              val (s2, _) = fb.run(s1)
              assert(fa.zipLeft(fb).run(s))(equalTo((s2, a)))
            }
          },
          test("zipRight") {
            check(genState, genState, genInt) { (fa, fb, s) =>
              val (s1, _) = fa.run(s)
              val (s2, b) = fb.run(s1)
              assert(fa.zipRight(fb).run(s))(equalTo((s2, b)))
            }
          },
          test("zipWith") {
            check(genState, genState, genIntIntToInt, genInt) { (fa, fb, f, s) =>
              val (s1, a) = fa.run(s)
              val (s2, b) = fb.run(s1)
              assert(fa.zipWith(fb)(f).run(s))(equalTo((s2, f(a, b))))
            }
          }
        ),
        suite("constructors")(
          test("get") {
            check(genInt) { s =>
              assert(State.get.run(s))(equalTo((s, s)))
            }
          },
          test("modify") {
            check(Gen.int, genIntToIntInt) { (s, f) =>
              assert(State.modify(f).run(s))(equalTo(f(s).swap))
            }
          },
          test("set") {
            check(genInt, genInt) { (s1, s2) =>
              assert(State.set(s2).run(s1))(equalTo((s2, ())))
            }
          },
          test("asState") {
            check(genInt, genInt, genInt) { (s1, s2, s3) =>
              assert(State.set(s2).asState(s3).run(s1))(equalTo((s3, ())))
            }
          },
          test("succeed") {
            check(genInt, genInt) { (s, a) =>
              assert(State.succeed(a).run(s))(equalTo((s, a)))
            }
          },
          test("unit") {
            check(genInt) { s =>
              assert(State.unit.run(s))(equalTo((s, ())))
            }
          },
          test("update") {
            check(genInt, genIntToInt) { (s, f) =>
              assert(State.update(f).run(s))(equalTo((f(s), ())))
            }
          }
        )
      ),
      suite("failure")(
        suite("methods")(
          test("either") {
            check(genInt, genInt) { (s1, e) =>
              val (s2, a) = ZPure.fail(e).either.run(s1)
              assert(s2)(equalTo(s1)) && assert(a)(isLeft(equalTo(e)))
            }
          },
          suite("none")(
            test("success") {
              check(genInt) { s =>
                assert(ZPure.succeed[Int, Option[Int]](None).none.getState.either.runResult(s))(
                  isRight(equalTo((s, ())))
                )
              }
            },
            test("failure") {
              check(genInt, genInt) { (s, a) =>
                assert(ZPure.succeed[Int, Option[Int]](Some(a)).none.getState.either.runResult(s))(isLeft(isNone))
              }
            }
          ),
          test("orElseFail") {
            check(genInt, genInt, genString) { (s1, e, e1) =>
              val errorOrUpdate = ZPure.fail(e).orElseFail(e1).getState.either.runResult(s1)
              assert(errorOrUpdate)(isLeft(equalTo(e1)))
            }
          },
          test("orElseOptional (Some case)") {
            check(genInt, genString, genString) { (s1, e, e1) =>
              val errorOrUpdate = ZPure.fail(Some(e)).orElseOptional(ZPure.fail(Some(e1))).getState.either.runResult(s1)
              assert(errorOrUpdate)(isLeft(equalTo(Option(e))))
            }
          },
          test("orElseOptional (None case)") {
            check(genInt, genString) { (s1, e) =>
              val errorOrUpdate =
                ZPure.fail(Option.empty[String]).orElseOptional(ZPure.fail(Some(e))).getState.either.runResult(s1)
              assert(errorOrUpdate)(isLeft(equalTo(Option(e))))
            }
          },
          test("orElseSucceed (Success case)") {
            implicit val canFail = CanFail
            check(genInt, genInt, genInt) { (s1, v, v1) =>
              val (_, a) = ZPure.succeed(v).orElseSucceed(v1).run(s1)
              assert(a)(equalTo(v))
            }
          },
          test("orElseSucceed (Failure case)") {
            check(genInt, genString, genInt) { (s1, e, v1) =>
              val (_, a) = ZPure.fail(e).orElseSucceed(v1).run(s1)
              assert(a)(equalTo(v1))
            }
          },
          test("orElseFallback (Success case)") {
            implicit val canFail = CanFail
            check(genInt, genInt, genInt, genInt) { (s1, s3, v, v1) =>
              val (s, a) = ZPure.succeed[Int, Int](v).orElseFallback(v1, s3).run(s1)
              assert(a)(equalTo(v)) && assert(s)(equalTo(s1))
            }
          },
          test("orElseFallback (Failure case)") {
            check(genInt, genInt, genString, genInt) { (s1, s3, e, v1) =>
              val (s, a) = ZPure.fail(e).orElseFallback(v1, s3).run(s1)
              assert(a)(equalTo(v1)) && assert(s)(equalTo(s3))
            }
          },
          suite("fold")(
            test("failure") {
              check(genInt, genInt, genIntToInt, genIntToInt) { (s1, e, failure, success) =>
                val (s2, a) = ZPure.fail(e).fold(failure, success).run(s1)
                assert(s2)(equalTo(s1)) && assert(a)(equalTo(failure(e)))
              }
            },
            test("success") {
              implicit val canFail = CanFail
              check(genInt, genInt, genIntToInt, genIntToInt) { (s1, a1, failure, success) =>
                val (s2, a2) = ZPure.succeed[Int, Int](a1).fold(failure, success).run(s1)
                assert(s2)(equalTo(s1)) && assert(a2)(equalTo(success(a1)))
              }
            }
          ),
          suite("foldM")(
            test("failure") {
              implicit val canFail = CanFail
              val failing          =
                ZPure.succeed[Int, Int](1).flatMap(n => if (n % 2 !== 0) ZPure.fail("fail") else ZPure.succeed(n))
              val result           = failing.foldM(
                _ => State.update[Int, Int](_ + 1) *> ZPure.succeed(0),
                a => State.update[Int, Int](_ + 2) *> ZPure.succeed(a)
              )
              assert(result.run(10))(equalTo((11, 0)))
            },
            test("success") {
              implicit val canFail = CanFail
              val failing          =
                ZPure.succeed[Int, Int](2).flatMap(n => if (n % 2 !== 0) ZPure.fail("fail") else ZPure.succeed(n))
              val result           = failing.foldM(
                _ => State.update[Int, Int](_ + 1) *> ZPure.succeed(0),
                a => State.update[Int, Int](_ + 2) *> ZPure.succeed(a)
              )
              assert(result.run(10))(equalTo((12, 2)))
            }
          ),
          suite("left methods")(
            suite("left")(
              test("failure") {
                val result = ZPure.fail("fail").left
                assert(result.getState.either.runResult(0))(isLeft(isSome(equalTo("fail"))))
              },
              test("right") {
                val result = ZPure.succeed[Int, Either[Nothing, Int]](Right(1)).left
                assert(result.getState.either.runResult(0))(isLeft(isNone))
              },
              test("left") {
                val result = ZPure.succeed[Int, Either[String, Int]](Left("Left")).left
                assert(result.getState.either.runResult(0))(isRight(equalTo((0, "Left"))))
              }
            ),
            suite("leftOrFail")(
              test("failure") {
                val result = ZPure.fail("fail").leftOrFail("oh crap")
                assert(result.getState.either.runResult(0))(isLeft(equalTo("fail")))
              },
              test("right") {
                val result = ZPure
                  .succeed[Int, Either[Nothing, Int]](Right(1))
                  .leftOrFail("oh crap")
                assert(result.getState.either.runResult(0))(isLeft(equalTo("oh crap")))
              },
              test("left") {
                val result = ZPure.succeed[Int, Either[String, Int]](Left("Left")).leftOrFail("oh crap")
                assert(result.getState.either.runResult(0))(isRight(equalTo((0, "Left"))))
              }
            ),
            suite("leftOrFailWith")(
              test("failure") {
                val result = ZPure.fail("fail").leftOrFailWith[Any, Any, String](_ => "Oh crap")
                assert(result.getState.either.runResult(0))(isLeft(equalTo("fail")))
              },
              test("right") {
                val result = ZPure
                  .succeed[Int, Either[Nothing, Int]](Right(1))
                  .leftOrFailWith[Any, Any, String](_ => "oh crap")
                assert(result.getState.either.runResult(0))(isLeft(equalTo("oh crap")))
              },
              test("left") {
                val result = ZPure.succeed[Int, Either[String, Int]](Left("Left")).leftOrFail("oh crap")
                assert(result.getState.either.runResult(0))(isRight(equalTo((0, "Left"))))
              }
            ),
            suite("leftOrFailWithException")(
              test("failure") {
                val result = ZPure.fail(new NoSuchElementException()).leftOrFailWithException
                assert(result.getState.either.runResult(0))(isLeft(isSubtype[NoSuchElementException](anything)))
              },
              test("right") {
                val result = ZPure.succeed[Int, Either[Nothing, Int]](Right(1)).leftOrFailWithException
                assert(result.getState.either.runResult(0))(isLeft(isSubtype[NoSuchElementException](anything)))
              },
              test("left") {
                val result = ZPure.succeed[Int, Either[String, Int]](Left("Left")).leftOrFailWithException
                assert(result.getState.either.runResult(0))(isRight(equalTo((0, "Left"))))
              }
            )
          ),
          suite("refineToOrDie")(
            test("success case") {
              check(genInt) { a =>
                assert(ZPure.attempt(a.toString.toInt).refineToOrDie[NumberFormatException].runEither)(
                  isRight(equalTo(a))
                )
              }
            },
            test("failure case") {
              implicit val throwableHash = Equal.ThrowableHash
              val exception: Throwable   = new NumberFormatException("""For input string: "a"""")
              assert(ZPure.attempt("a".toInt).refineToOrDie[NumberFormatException].runEither)(
                isLeft(equalTo(exception))
              )
            }
          ),
          suite("right methods")(
            suite("right")(
              test("failure") {
                val result = ZPure.fail("fail").right
                assert(result.getState.either.runResult(0))(isLeft(isSome(equalTo("fail"))))
              },
              test("right") {
                val result = ZPure.succeed[Int, Either[Nothing, String]](Right("Right")).right
                assert(result.getState.either.runResult(0))(isRight(equalTo((0, "Right"))))
              },
              test("left") {
                val result = ZPure.succeed[Int, Either[Int, Nothing]](Left(1)).right
                assert(result.getState.either.runResult(0))(isLeft(isNone))
              }
            ),
            suite("rightOrFail")(
              test("failure") {
                val result = ZPure.fail("fail").rightOrFail("oh crap")
                assert(result.getState.either.runResult(0))(isLeft(equalTo("fail")))
              },
              test("right") {
                val result = ZPure
                  .succeed[Int, Either[Nothing, Int]](Right(1))
                  .rightOrFail("oh crap")
                assert(result.getState.either.runResult(0))(isRight(equalTo((0, 1))))
              },
              test("left") {
                val result = ZPure.succeed[Int, Either[String, Int]](Left("Left")).rightOrFail("oh crap")
                assert(result.getState.either.runResult(0))(isLeft(equalTo("oh crap")))
              }
            ),
            suite("rightOrFailWith")(
              test("failure") {
                val result = ZPure.fail("fail").rightOrFailWith[Any, Any, String](_ => "Oh crap")
                assert(result.getState.either.runResult(0))(isLeft(equalTo("fail")))
              },
              test("right") {
                val result = ZPure
                  .succeed[Int, Either[Nothing, Int]](Right(1))
                  .rightOrFailWith[Any, Int, String](_ => "oh crap")
                assert(result.getState.either.runResult(0))(isRight(equalTo((0, 1))))
              },
              test("left") {
                val result = ZPure.succeed[Int, Either[String, Int]](Left("Left")).rightOrFail("oh crap")
                assert(result.getState.either.runResult(0))(isLeft(equalTo("oh crap")))
              }
            ),
            suite("rightOrFailWithException")(
              test("failure") {
                val result = ZPure.fail(new NoSuchElementException()).rightOrFailWithException
                assert(result.getState.either.runResult(0))(isLeft(isSubtype[NoSuchElementException](anything)))
              },
              test("right") {
                val result = ZPure.succeed[Int, Either[Nothing, Int]](Right(1)).rightOrFailWithException
                assert(result.getState.either.runResult(0))(isRight(equalTo((0, 1))))
              },
              test("left") {
                val result = ZPure.succeed[Int, Either[String, Int]](Left("Left")).rightOrFailWithException
                assert(result.getState.either.runResult(0))(isLeft(isSubtype[NoSuchElementException](anything)))
              }
            )
          ),
          suite("some")(
            test("success (Some)") {
              check(genInt, genInt, genInt) { (s1, s2, a) =>
                val successSome: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]] = ZPure.modify(_ => (Some(a), s2))
                val result: ZPure[Nothing, Int, Int, Any, Option[Nothing], Int]      = successSome.some
                assert(result.getState.either.runResult(s1))(isRight(equalTo((s2, a))))
              }
            },
            test("success (None)") {
              check(genInt) { s =>
                val successNone: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]] = ZPure.succeed(None)
                val result: ZPure[Nothing, Int, Int, Any, Option[Nothing], Int]      = successNone.some
                assert(result.getState.either.runResult(s))(isLeft(isNone))
              }
            },
            test("failure") {
              check(genInt, genInt) { (s, e) =>
                val failure: ZPure[Nothing, Int, Int, Any, Int, Option[Int]] = ZPure.fail(e)
                val result: ZPure[Nothing, Int, Int, Any, Option[Int], Int]  = failure.some
                assert(result.getState.either.runResult(s))(isLeft(isSome(equalTo(e))))
              }
            }
          ),
          suite("someOrElse")(
            test("success (Some)") {
              check(genInt, genInt, genInt, genInt) { (s1, s2, a, default) =>
                val successSome: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]] = ZPure.modify(_ => (Some(a), s2))
                val result: ZPure[Nothing, Int, Int, Any, Nothing, Int]              = successSome.someOrElse(default)
                assert(result.run(s1))(equalTo((s2, a)))
              }
            },
            test("success (None)") {
              check(genInt, genInt, genInt) { (s1, s2, default) =>
                val successNone: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]] = ZPure.modify(_ => (None, s2))
                val result: ZPure[Nothing, Int, Int, Any, Nothing, Int]              = successNone.someOrElse(default)
                assert(result.run(s1))(equalTo((s2, default)))
              }
            },
            test("failure") {
              check(genInt, genInt, genInt) { (s, e, default) =>
                val failure: ZPure[Nothing, Int, Int, Any, Int, Option[Int]] = ZPure.fail(e)
                val result: ZPure[Nothing, Int, Int, Any, Int, Int]          = failure.someOrElse(default)
                assert(result.getState.either.runResult(s))(isLeft(equalTo(e)))
              }
            }
          ),
          suite("someOrElseM")(
            test("success (Some)") {
              check(genInt, genInt, genInt) { (s1, s2, a) =>
                val successSome: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]] = ZPure.modify(_ => (Some(a), s2))
                val that: ZPure[Nothing, Int, Int, Any, Unit, Int]                   = ZPure.fail(())
                val result: ZPure[Nothing, Int, Int, Any, Unit, Int]                 = successSome.someOrElseM(that)
                assert(result.getState.either.runResult(s1))(isRight(equalTo((s2, a))))
              }
            },
            test("success (None)") {
              check(genInt, genInt, genIntToInt, genIntToInt) { (s, a, f1, f2) =>
                val successNone: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]] =
                  ZPure.modify(s1 => (None, f1(s1)))
                val that: ZPure[Nothing, Int, Int, Any, Nothing, Int]                = ZPure.modify(s2 => (a, f2(s2)))
                val result: ZPure[Nothing, Int, Int, Any, Nothing, Int]              = successNone.someOrElseM(that)
                assert(result.run(s))(equalTo((f2(f1(s)), a)))
              }
            },
            test("failure") {
              check(genInt, genInt, genState) { (s, e, that) =>
                val failure: ZPure[Nothing, Int, Int, Any, Int, Option[Int]] = ZPure.fail(e)
                val result: ZPure[Nothing, Int, Int, Any, Int, Int]          = failure.someOrElseM(that)
                assert(result.getState.either.runResult(s))(isLeft(equalTo(e)))
              }
            }
          ),
          suite("someOrFail")(
            test("success (Some)") {
              check(genInt, genInt, genInt, genInt) { (s1, s2, e, a) =>
                val successSome: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]] = ZPure.modify(_ => (Some(a), s2))
                val result: ZPure[Nothing, Int, Int, Any, Int, Int]                  = successSome.someOrFail(e)
                assert(result.getState.either.runResult(s1))(isRight(equalTo((s2, a))))
              }
            },
            test("success (None)") {
              check(genInt, genInt) { (s, e) =>
                val successNone: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]] = ZPure.succeed(None)
                val result: ZPure[Nothing, Int, Int, Any, Int, Int]                  = successNone.someOrFail(e)
                assert(result.getState.either.runResult(s))(isLeft(equalTo(e)))
              }
            },
            test("failure") {
              check(genInt, genInt, genInt) { (s, e1, e2) =>
                val failure: ZPure[Nothing, Int, Int, Any, Int, Option[Int]] = ZPure.fail(e1)
                val result: ZPure[Nothing, Int, Int, Any, Int, Int]          = failure.someOrFail(e2)
                assert(result.getState.either.runResult(s))(isLeft(equalTo(e1)))
              }
            }
          ),
          suite("someOrFailException")(
            test("success (Some)") {
              check(genInt, genInt, genInt) { (s1, s2, a) =>
                val successSome: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]]   = ZPure.modify(_ => (Some(a), s2))
                val result: ZPure[Nothing, Int, Int, Any, NoSuchElementException, Int] = successSome.someOrFailException
                assert(result.getState.either.runResult(s1))(isRight(equalTo((s2, a))))
              }
            },
            test("success (None)") {
              check(genInt) { (s) =>
                val successNone: ZPure[Nothing, Int, Int, Any, Nothing, Option[Int]]   = ZPure.succeed(None)
                val result: ZPure[Nothing, Int, Int, Any, NoSuchElementException, Int] = successNone.someOrFailException
                assert(result.getState.either.runResult(s))(isLeft(anything))
              }
            },
            test("failure") {
              check(genInt, genInt) { (s, e) =>
                val failure: ZPure[Nothing, Int, Int, Any, Int, Option[Int]] = ZPure.fail(e)
                val result: ZPure[Nothing, Int, Int, Any, Any, Int]          = failure.someOrFailException
                assert(result.getState.either.runResult(s))(isLeft(isSubtype[Int](equalTo(e))))
              }
            }
          )
        ),
        suite("reject")(
          test("success") {
            check(genInt, genInt, genInt) { (s1, a1, e1) =>
              val result = ZPure.succeed[Int, Int](a1).reject { case _ =>
                e1
              }
              assert(result.getState.either.runResult(s1))(isLeft(equalTo(e1)))
            }
          },
          test("failure") {
            check(genInt, genInt, genInt) { (s1, a1, e1) =>
              val result = ZPure.succeed[Int, Int](a1).reject {
                case _ if false => e1
              }
              assert(result.getState.either.runResult(s1))(isRight(equalTo((s1, a1))))
            }
          }
        ),
        suite("rejectM")(
          test("success") {
            check(genInt, genInt, genInt) { (s1, a1, e1) =>
              val result = ZPure.succeed[Int, Int](a1).rejectM { case _ =>
                ZPure.succeed[Int, Int](e1)
              }
              assert(result.getState.either.runResult(s1))(isLeft(equalTo(e1)))
            }
          },
          test("failure") {
            check(genInt, genInt, genInt) { (s1, a1, e1) =>
              val result = ZPure.succeed[Int, Int](a1).rejectM {
                case _ if false => ZPure.succeed[Int, Int](e1)
              }
              assert(result.getState.either.runResult(s1))(isRight(equalTo((s1, a1))))
            }
          }
        ),
        suite("constructors")(
          test("fail") {
            check(genInt) { e =>
              assert(ZPure.fail(e).getState.either.run)(isLeft(equalTo(e)))
            }
          },
          test("fromEither (Left)") {
            check(genString) { l =>
              assert(ZPure.fromEither(Left(l)).runEither)(isLeft(equalTo(l)))
            }
          },
          test("fromEither (Right)") {
            check(genString) { r =>
              val (_, a) = ZPure.fromEither(Right(r)).run(())
              assert(a)(equalTo(r))
            }
          },
          test("fromOption (None)") {
            assert(ZPure.fromOption(Option.empty[String]).runEither)(isLeft(isUnit))
          },
          test("fromOption (Some)") {
            check(genInt) { a =>
              assert(ZPure.fromOption(Option(a)).runEither)(isRight(equalTo(a)))
            }
          },
          test("fromTry (Success case)") {
            check(genInt) { a =>
              assert(ZPure.fromTry(Try(a)).runEither)(isRight(equalTo(a)))
            }
          },
          test("fromTry (Failure case)") {
            implicit val throwableHash = Equal.ThrowableHash
            val exception: Throwable   = new NumberFormatException("""For input string: "a"""")
            assert(ZPure.fromTry(Try("a".toInt)).runEither)(isLeft(equalTo(exception)))
          },
          test("fromEffect (Success case)") {
            check(genInt) { a =>
              assert(ZPure.attempt(a).runEither)(isRight(equalTo(a)))
            }
          },
          test("fromEffect (Failure case)") {
            implicit val throwableHash = Equal.ThrowableHash
            val exception: Throwable   = new NumberFormatException("""For input string: "a"""")
            assert(ZPure.attempt("a".toInt).runEither)(isLeft(equalTo(exception)))
          },
          suite("modifyEither")(
            test("success") {
              assert(ZPure.modifyEither((_: Int) => Right(("success", 1))).run(0))(equalTo((1, "success")))
            },
            test("failure") {
              assert(ZPure.modifyEither((_: Int) => Left("error")).getState.either.runResult(0))(
                isLeft(equalTo("error"))
              )
            }
          )
        ),
        test("parallel errors example") {
          def validateName(s: String): ZPure[Nothing, Unit, Unit, Any, String, String]               =
            if (s == "John Doe") ZPure.succeed(s) else ZPure.fail("Wrong name!")
          def validateAge(age: Int): ZPure[Nothing, Unit, Unit, Any, String, Int]                    =
            if (age >= 18) ZPure.succeed(age) else ZPure.fail("Under age")
          def validateAuthorized(authorized: Boolean): ZPure[Nothing, Unit, Unit, Any, String, Unit] =
            if (authorized) ZPure.unit else ZPure.fail("Not authorized")
          val validation                                                                             =
            validateName("Jane Doe") zipPar validateAge(17) zipPar validateAuthorized(false)
          val result                                                                                 = validation.sandbox.either.run
          assert(result)(
            isLeft(equalTo(Cause("Wrong name!") && Cause("Under age") && Cause("Not authorized")))
          )
        },
        test("state is restored after failure") {
          val foo: ZPure[Nothing, String, Int, Any, Nothing, Unit] = ZPure.set(3)
          val bar: ZPure[Nothing, Int, String, Any, Nothing, Unit] = ZPure.set("bar")
          val zPure                                                = for {
            _ <- (foo *> ZPure.fail("baz") *> bar).either
            s <- ZPure.get
          } yield s
          assert(zPure.provideState("").run)(equalTo(""))
        }
      ),
      suite("log")(
        test("log example") {
          val computation: ZPure[String, Unit, Unit, Any, Nothing, Int] = for {
            a <- ZPure.succeed(1 + 1)
            _ <- ZPure.log("plus")
            b <- ZPure.succeed(a * 3)
            _ <- ZPure.log("times")
          } yield b
          assert(computation.runLog)(equalTo((Chunk("plus", "times"), 6)))
        },
        test("log is not cleared after failure") {
          def log(i: Int): ZPure[Int, String, String, Any, Nothing, Unit] = ZPure.log(i)
          val zPure                                                       =
            for {
              _ <- (log(1) *> ZPure.fail("baz")).either
              _ <- log(2)
              _ <- (log(3) *> ZPure.fail("baz")).either
              _ <- (log(4) *> (if (false) ZPure.fail("baz") else ZPure.unit)).either
            } yield ()
          assert(zPure.keepLogOnError.provideState("").runLog)(equalTo((Chunk(1, 2, 3, 4), ())))
        },
        test("log is not cleared after failure with keepLogOnError") {
          def log(i: Int): ZPure[Int, String, String, Any, Nothing, Unit] = ZPure.log(i)
          val zPure                                                       =
            for {
              _ <- (log(1) *> ZPure.fail("baz")).either
              _ <- log(2)
              _ <- (log(3) *> ZPure.fail("baz")).either
              _ <- (log(4) *> (if (false) ZPure.fail("baz") else ZPure.unit)).either
            } yield ()
          assert(zPure.keepLogOnError.provideState("").runLog)(equalTo((Chunk(1, 2, 3, 4), ())))
        },
        test("log is cleared after failure with clearLogOnError") {
          def log(i: Int): ZPure[Int, String, String, Any, Nothing, Unit] = ZPure.log(i)
          val zPure                                                       =
            for {
              _ <- (log(1) *> ZPure.fail("baz")).either
              _ <- log(2)
              _ <- (log(3) *> ZPure.fail("baz")).either
              _ <- (log(4) *> (if (false) ZPure.fail("baz") else ZPure.unit)).either
            } yield ()
          assert(zPure.clearLogOnError.provideState("").runLog)(equalTo((Chunk(2, 4), ())))
        },
        test("combine clearLogOnError and keepLogOnError") {
          def log(i: Int): ZPure[Int, String, String, Any, Nothing, Unit] = ZPure.log(i)
          val zPure                                                       =
            for {
              _ <- (log(1) *> ZPure.fail("baz")).either.keepLogOnError
              _ <- log(2)
              _ <- (log(3) *> ZPure.fail("baz")).either.clearLogOnError
            } yield ()
          assert(zPure.provideState("").runLog)(equalTo((Chunk(1, 2), ())))
        },
        test("log is not cleared after failure with keepLogOnError when the whole computation fails") {
          def log(i: Int): ZPure[Int, String, String, Any, Nothing, Unit] = ZPure.log(i)
          val zPure                                                       = log(1) *> ZPure.fail("baz")
          assert(zPure.keepLogOnError.runAll("")._1)(equalTo(Chunk(1)))
        },
        test("log is cleared after failure with clearLogOnError when the whole computation fails") {
          def log(i: Int): ZPure[Int, String, String, Any, Nothing, Unit] = ZPure.log(i)
          val zPure                                                       = log(1) *> ZPure.fail("baz")
          assert(zPure.clearLogOnError.runAll("")._1)(equalTo(Chunk()))
        },
        test("clearLogOnError should not affect the overall result") {
          def log(i: Int): ZPure[Int, String, String, Any, Nothing, Unit] = ZPure.log(i)
          val zPure                                                       = log(1) *> ZPure.fail("baz")
          assert(zPure.clearLogOnError.runAll("")._2)(isLeft(anything))
        }
      ),
      test("toZIO infers correctly") {
        for {
          result <- ZPure.succeed(1).toZIO
          _      <- ZIO.unit
        } yield assertTrue(result == 1)
      },
      test("unless") {
        val zPure =
          for {
            _    <- ZPure.unless(true)(ZPure.set(1))
            val1 <- ZPure.get
            _    <- ZPure.unless(false)(ZPure.set(2))
            val2 <- ZPure.get
          } yield assert(val1)(equalTo(0)) &&
            assert(val2)(equalTo(2))
        zPure.runResult(0)
      },
      test("when") {
        val zPure =
          for {
            _    <- ZPure.when(false)(ZPure.set(1))
            val1 <- ZPure.get
            _    <- ZPure.when(true)(ZPure.set(2))
            val2 <- ZPure.get
          } yield assert(val1)(equalTo(0)) &&
            assert(val2)(equalTo(2))
        zPure.runResult(0)
      },
      test("whenCase") {
        val v1: Option[Int] = None
        val v2: Option[Int] = Some(0)
        val zPure           = for {
          _    <- ZPure.whenCase(v1) { case Some(_) => ZPure.set(true) }
          res1 <- ZPure.get
          _    <- ZPure.whenCase(v2) { case Some(_) => ZPure.set(true) }
          res2 <- ZPure.get
        } yield assert(res1)(isFalse) && assert(res2)(isTrue)
        zPure.runResult(false)
      }
    )

}
