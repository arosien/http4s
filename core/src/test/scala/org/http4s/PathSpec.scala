package org.http4s

import org.specs2.mutable.Specification
import org.http4s.{ Path => FPath }
import play.api.libs.iteratee.Enumerator

class PathSpec extends Specification {
  "Path" should {
    "/foo/bar" in {
      FPath("/foo/bar").toList mustEqual List("foo", "bar")
    }

    "foo/bar" in {
      FPath("foo/bar").toList mustEqual List("foo", "bar")
    }

    ":? extractor" in {
      ((FPath("/test.json") :? Map[String, Seq[String]]()) match {
        case Root / "test.json" :? _ => true
        case _                       => false
      }) must beTrue
    }

    ":? extractor with ParamMatcher" in {
      object A extends ParamMatcher("a")
      object B extends ParamMatcher("b")

      ((FPath("/test.json") :? Map[String, Seq[String]]("a" -> Seq("1"), "b" -> Seq("2"))) match {
        case Root / "test.json" :? A(a) => a == "1"
        case _                          => false
      }) must beTrue

      ((FPath("/test.json") :? Map[String, Seq[String]]("a" -> Seq("1"), "b" -> Seq("2"))) match {
        case Root / "test.json" :? B(b) => b == "2"
        case _                          => false
      }) must beTrue

      ((FPath("/test.json") :? Map[String, Seq[String]]("a" -> Seq("1"), "b" -> Seq("2"))) match {
        case Root / "test.json" :? (A(a) :& B(b)) => a == "1" && b == "2"
        case _                                    => false
      }) must beTrue

      ((FPath("/test.json") :? Map[String, Seq[String]]("a" -> Seq("1"), "b" -> Seq("2"))) match {
        case Root / "test.json" :? (B(b) :& A(a)) => a == "1" && b == "2"
        case _                                    => false
      }) must beTrue
    }

    ":? extractor with IntParamMatcher and LongParamMatcher" in {
      object I extends IntParamMatcher("i")
      object L extends LongParamMatcher("l")
      object D extends DoubleParamMatcher("d")

      ((FPath("/test.json") :? Map[String, Seq[String]]("i" -> Seq("1"), "l" -> Seq("2147483648"), "d" -> Seq("1.3"))) match {
        case Root / "test.json" :? (I(i) :& L(l) :& D(d)) => i == 1 && l == 2147483648L && d == 1.3D
        case _                                    => false
      }) must beTrue

    }

    "~ extractor on Path" in {
      (FPath("/foo.json") match {
        case Root / "foo" ~ "json" => true
        case _                     => false
      }) must beTrue
    }

    "~ extractor on filename foo.json" in {
      ("foo.json" match {
        case "foo" ~ "json" => true
        case _              => false
      }) must beTrue
    }

    "~ extractor on filename foo" in {
      ("foo" match {
        case "foo" ~ "" => true
        case _          => false
      }) must beTrue
    }

    "-> extractor" in {
      val req = Request(requestMethod = Method.Get, pathInfo = "/test.json", body = Enumerator.eof[Chunk])
      (req match {
        case Method.Get(Root / "test.json") => true
        case _                              => false
      }) must beTrue
    }

    "Root extractor" in {
      (FPath("/") match {
        case Root => true
        case _    => false
      }) must beTrue
    }

    "Root extractor, no partial match" in {
      (FPath("/test.json") match {
        case Root => true
        case _    => false
      }) must beFalse
    }

    "Root extractor, empty path" in {
      (FPath("") match {
        case Root => true
        case _    => false
      }) must beTrue
    }

    "/ extractor" in {
      (FPath("/1/2/3/test.json") match {
        case Root / "1" / "2" / "3" / "test.json" => true
        case _                                    => false
      }) must beTrue
    }

    "Integer extractor" in {
      (FPath("/user/123") match {
        case Root / "user" / Int(userId) => userId == 123
        case _                           => false
      }) must beTrue
    }

    "Integer extractor, invalid int" in {
      (FPath("/user/invalid") match {
        case Root / "user" / Int(userId) => true
        case _                           => false
      }) must beFalse
    }

    "Integer extractor, number format error" in {
      (FPath("/user/2147483648") match {
        case Root / "user" / Int(userId) => true
        case _                           => false
      }) must beFalse
    }

    "Long extractor" in {
      (FPath("/user/123") match {
        case Root / "user" / Long(userId) => userId == 123
        case _                            => false
      }) must beTrue
    }

    "Long extractor, invalid int" in {
      (FPath("/user/invalid") match {
        case Root / "user" / Long(userId) => true
        case _                            => false
      }) must beFalse
    }

    "Long extractor, number format error" in {
      (FPath("/user/9223372036854775808") match {
        case Root / "user" / Long(userId) => true
        case _                            => false
      }) must beFalse
    }
  }
}