package sttp.tapir.annotations

import java.nio.charset.StandardCharsets
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.{Header => ModelHeader, QueryParams => ModelQueryParams}
import sttp.model.headers.{CookieValueWithMeta, CookieWithMeta, Cookie => ModelCookie}
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.model.UsernamePassword
import sttp.tapir.EndpointIO._
import sttp.tapir.EndpointIO.annotations._
import sttp.tapir.EndpointInput._
import sttp.tapir.EndpointInput.Auth._
import sttp.tapir.RawBodyType.StringBody

object JsonCodecs {
  implicit val stringJsonCodec: JsonCodecMock[String] = new JsonCodecMock[String]
  implicit val booleanJsonCodec: JsonCodecMock[Boolean] = new JsonCodecMock[Boolean]
}

final case class TapirRequestTest1(
    @query
    field1: Int,
    @query("another-field-name")
    field2: String,
    @cookie
    cookie: Boolean,
    @cookie("another-cookie-name")
    namedCookie: Boolean,
    @header
    header: Long,
    @header("another-header-name")
    namedHeader: Int,
    @jsonbody
    body: String
)

final case class TapirRequestTest2(
    @body(StringBody(StandardCharsets.UTF_8), CodecFormat.Json())
    body: Boolean
)

final case class TapirRequestTest3(
    @params
    params: ModelQueryParams,
    @headers
    headers: List[ModelHeader],
    @cookies
    cookies: List[ModelCookie]
)

final case class TapirRequestTest4(
    @apikey(challenge = WWWAuthenticate.apiKey("api realm")) @query
    param1: Int,
    @basic(challenge = WWWAuthenticate.basic("basic realm"))
    basicAuth: UsernamePassword,
    @bearer(challenge = WWWAuthenticate.bearer("bearer realm"))
    token: String
)

final case class TapirRequestTest5(
    @query
    @description("field-description")
    field1: Int,
    @cookie
    @Schema.annotations.deprecated
    cookie: Boolean
)

@endpointInput("some/{field5}/path/{field2}")
final case class TapirRequestTest6(
    @query
    field1: Int,
    @path
    @description("path-description")
    field2: Boolean,
    @query
    field3: Long,
    @query
    field4: String,
    @path
    @apikey
    field5: Int
)

final case class TapirRequestTest7(
    @apikey @query
    @securitySchemeName(name = "secapi")
    param1: Int,
    @basic
    @securitySchemeName(name = "secbasic")
    basicAuth: UsernamePassword,
    @securitySchemeName(name = "secbearer")
    @bearer
    token: String
)

final case class TapirRequestTest8(
    @query
    @description("field-8-1")
    @example(9)
    @Schema.annotations.description("field-8-2")
    @Schema.annotations.encodedExample(10)
    @Schema.annotations.default(11)
    @Schema.annotations.format("tel")
    @Schema.annotations.validate(Validator.min(0))
    @Schema.annotations.deprecated
    field: Int
)

final case class TapirResponseTest1(
    @header
    header1: Int,
    @header("another-header-name")
    header2: Boolean,
    @setCookie("cookie-name")
    setCookie: CookieValueWithMeta,
    @jsonbody
    body: String,
    @statusCode
    status: StatusCode
)

final case class TapirResponseTest2(
    @headers
    headers: List[ModelHeader],
    @cookies
    cookies: List[ModelCookie],
    @setCookies
    setCookies: List[CookieWithMeta]
)

class DeriveEndpointIOTest extends AnyFlatSpec with Matchers with Tapir {

  "@endpointInput" should "derive correct input for @query, @cookie, @header" in {
    import JsonCodecs._

    val expectedInput = query[Int]("field1")
      .and(query[String]("another-field-name"))
      .and(cookie[Boolean]("cookie"))
      .and(cookie[Boolean]("another-cookie-name"))
      .and(header[Long]("header"))
      .and(header[Int]("another-header-name"))
      .and(anyJsonBody[String])
      .mapTo[TapirRequestTest1]

    compareTransputs(EndpointInput.derived[TapirRequestTest1], expectedInput) shouldBe true
  }

  it should "derive correct input for dealised bodies" in {
    import JsonCodecs._

    val expectedInput = anyJsonBody[Boolean].mapTo[TapirRequestTest2]

    compareTransputs(EndpointInput.derived[TapirRequestTest2], expectedInput) shouldBe true
  }

  it should "derive correct input for @queries, @headers, @cookies" in {
    val expectedInput = queryParams.and(headers).and(cookies).mapTo[TapirRequestTest3]

    compareTransputs(EndpointInput.derived[TapirRequestTest3], expectedInput) shouldBe true
  }

  it should "derive correct input for auth annotations" in {
    val expectedInput = TapirAuth
      .apiKey(query[Int]("param1"), challenge = WWWAuthenticate.apiKey("api realm"))
      .and(TapirAuth.basic[UsernamePassword](challenge = WWWAuthenticate.basic("basic realm")))
      .and(TapirAuth.bearer[String](challenge = WWWAuthenticate.bearer("bearer realm")))
      .mapTo[TapirRequestTest4]

    compareTransputs(EndpointInput.derived[TapirRequestTest4], expectedInput) shouldBe true
  }

  it should "derive correct input for auth annotations with named security schemes" in {
    val expectedInput = TapirAuth
      .apiKey(query[Int]("param1"))
      .securitySchemeName("secapi")
      .and(TapirAuth.basic[UsernamePassword]().securitySchemeName("secbasic"))
      .and(TapirAuth.bearer[String]().securitySchemeName("secbearer"))
      .mapTo[TapirRequestTest7]

    compareTransputs(EndpointInput.derived[TapirRequestTest7], expectedInput) shouldBe true
  }

  it should "derive input with descriptions" in {
    val expectedInput = query[Int]("field1")
      .description("field-description")
      .and(cookie[Boolean]("cookie").deprecated())
      .mapTo[TapirRequestTest5]

    compareTransputs(EndpointInput.derived[TapirRequestTest5], expectedInput) shouldBe true
  }

  it should "derive input with paths" in {
    val derivedInput = EndpointInput.derived[TapirRequestTest6]

    val expectedInput = "some"
      .and(TapirAuth.apiKey(path[Int]("field5")))
      .and("path")
      .and(path[Boolean]("field2").description("path-description"))
      .and(query[Int]("field1"))
      .and(query[Long]("field3"))
      .and(query[String]("field4"))
      .map[TapirRequestTest6] { (t: (Int, Boolean, Int, Long, String)) =>
        TapirRequestTest6(t._3, t._2, t._4, t._5, t._1)
      }((t: TapirRequestTest6) => (t.field5, t.field2, t.field1, t.field3, t.field4))

    compareTransputs(derivedInput, expectedInput) shouldBe true
  }

  it should "derive correct input with schema annotations" in {
    val expectedInput = query[Int]("field")
      .description("field-8-1")
      .example(9)
      .default(11)
      .schema(_.format("tel").encodedExample(10).description("field-8-2"))
      .validate(Validator.min(0))
      .deprecated()
      .mapTo[TapirRequestTest8]

    val derived = EndpointInput.derived[TapirRequestTest8].asInstanceOf[EndpointInput.Query[TapirRequestTest8]]

    compareTransputs(EndpointInput.derived[TapirRequestTest8], expectedInput) shouldBe true
    derived.codec.schema.description shouldBe expectedInput.codec.schema.description
    derived.codec.schema.encodedExample shouldBe expectedInput.codec.schema.encodedExample
    derived.codec.schema.format shouldBe expectedInput.codec.schema.format
    derived.codec.schema.default shouldBe expectedInput.codec.schema.default

    derived.codec.schema.applyValidation(TapirRequestTest8(-1)) should not be empty
    derived.codec.schema.applyValidation(TapirRequestTest8(1)) shouldBe empty
  }

  it should "not compile if there is field without annotation" in {
    assertDoesNotCompile("""
      final case class Test(
        @header
        h: Int,
        @query
        q: Boolean,
        i: Long
      )

      object Test {
        EndpointInput.derived[Test]
      }
    """)
  }

  it should "not compile if there are two body annotations" in {
    assertDoesNotCompile("""
      final case class Test(
        @jsonbody
        body1: Int,
        @xmlbody
        body2: Long
      )

      object Test {
        EndpointInput.derived[Test]
      }
    """)
  }

  it should "not compile for alone @apikey" in {
    assertDoesNotCompile("""
      final case class Test(
        @apikey
        query: Int
      )

      object Test {
        EndpointInput.derived[Test]
      }
    """)
  }

  it should "not compile for body without JSON codec" in {
    assertDoesNotCompile("""
      final case class Test(
        @jsonbody
        query: Int
      )

      object Test {
        EndpointInput.derived[Test]
      }
    """)
  }

  it should "not compile when not all paths are captured in case class" in {
    assertDoesNotCompile("""
      final case class Test(
        @query
        field1: Int,
        @path
        field2: String
      )
      object Test {
        val input = EndpointInput.derived[Test]("/asdf/{field2}/{field3}")
      }
    """)
  }

  it should "not compile when not all paths are captured in path" in {
    assertDoesNotCompile("""
      final case class Test(
        @query
        field1: Int,
        @path
        field2: String,
        @path
        field3: Long
      )
      object Test {
        val input = EndpointInput.derived[Test]("/asdf/{field2}")
      }
    """)
  }

  it should "not compile when path contains duplicated variable" in {
    assertDoesNotCompile("""
      final case class Test(
        @query
        field1: Int,
        @path
        field2: String
      )
      object Test {
        val input = EndpointInput.derived[Test]("/asdf/{field2}/{field2}")
      }
    """)
  }

  "@endpointOutput" should "devive correct output for @header, @setCookie, @statusCode" in {
    import JsonCodecs._

    val expectedOutput = header[Int]("header1")
      .and(header[Boolean]("another-header-name"))
      .and(setCookie("cookie-name"))
      .and(anyJsonBody[String])
      .and(statusCode)
      .mapTo[TapirResponseTest1]

    compareTransputs(EndpointOutput.derived[TapirResponseTest1], expectedOutput) shouldBe true
  }

  it should "derive correct output for @headers, @cookies, @setCookies" in {
    val expectedOutput = headers.and(cookies).and(setCookies).mapTo[TapirResponseTest2]

    compareTransputs(EndpointOutput.derived[TapirResponseTest2], expectedOutput) shouldBe true
  }

  it should "not compile if there is field without annotation" in {
    assertDoesNotCompile("""
      final case class Test(
        @header
        h: Int,
        @cookie
        q: Boolean,
        i: Long
      )

      object Test {
        EndpointOutput.derived[Test]
      }
    """)
  }

  it should "not compile if there are two body annotations" in {
    assertDoesNotCompile("""
      final case class Test(
        @jsonbody
        body1: Int,
        @xmlbody
        body2: Long
      )

      object Test {
        EndpointOutput.derived[Test]
      }
    """)
  }

  it should "not compile for wrong field type" in {
    assertDoesNotCompile("""
      final case class Test(
        @setCookies
        cookies: List[Int]
      )

      object Test {
        EndpointOutput.derived[Test]
      }
    """)
  }

  def compareTransputs(left: EndpointTransput[_], right: EndpointTransput[_]): Boolean =
    (left, right) match {
      case (EndpointInput.Pair(left1, right1, _, _), EndpointInput.Pair(left2, right2, _, _)) =>
        compareTransputs(left1, left2) && compareTransputs(right1, right2)
      case (EndpointInput.MappedPair(input1, _), EndpointInput.MappedPair(input2, _)) =>
        compareTransputs(input1, input2)
      case (FixedMethod(m1, _, info1), FixedMethod(m2, _, info2)) =>
        m1 == m2 && info1 == info2
      case (FixedPath(s1, _, info1), FixedPath(s2, _, info2)) =>
        s1 == s2 && info1 == info2
      case (PathCapture(name1, _, info1), PathCapture(name2, _, info2)) =>
        name1 == name2 && info1 == info2
      case (PathsCapture(_, info1), PathsCapture(_, info2)) =>
        info1 == info2
      case (Query(name1, _, info1), Query(name2, _, info2)) =>
        name1 == name2 && info1 == info2
      case (QueryParams(_, info1), QueryParams(_, info2)) =>
        info1 == info2
      case (Cookie(name1, _, info1), Cookie(name2, _, info2)) =>
        name1 == name2 && info1 == info2
      case (l, r) => // TODO: remove work-around after https://github.com/lampepfl/dotty/issues/12241 is fixed
        (l, r) match {
          case (ExtractFromRequest(_, info1), ExtractFromRequest(_, info2)) =>
            info1 == info2
          case (ApiKey(input1, challenge1, securitySchemeName1), ApiKey(input2, challenge2, securitySchemeName2)) =>
            challenge1 == challenge2 && securitySchemeName1 == securitySchemeName2 && compareTransputs(input1, input2)
          case (Http(scheme1, input1, challenge1, securitySchemeName1), Http(scheme2, input2, challenge2, securitySchemeName2)) =>
            challenge1 == challenge2 && scheme1 == scheme2 && securitySchemeName1 == securitySchemeName2 && compareTransputs(input1, input2)
          case (Body(bodyType1, _, info1), Body(bodyType2, _, info2)) =>
            bodyType1 == bodyType2 && info1 == info2
          case (FixedHeader(h1, _, info1), FixedHeader(h2, _, info2)) =>
            h1 == h2 && info1 == info2
          case (Header(name1, _, info1), Header(name2, _, info2)) =>
            name1 == name2 && info1 == info2
          case (Headers(_, info1), Headers(_, info2)) =>
            info1 == info2
          case (EndpointOutput.StatusCode(codes1, _, info1), EndpointOutput.StatusCode(codes2, _, info2)) =>
            codes1 == codes2 && info1 == info2
          // mapped pairs
          case (EndpointIO.MappedPair(io1, _), EndpointIO.MappedPair(io2, _)) =>
            compareTransputs(io1, io2)
          case (EndpointOutput.MappedPair(io1, _), EndpointOutput.MappedPair(io2, _)) =>
            compareTransputs(io1, io2)
          case (EndpointOutput.MappedPair(io1, _), EndpointIO.MappedPair(io2, _)) =>
            compareTransputs(io1, io2)
          case (EndpointIO.MappedPair(io1, _), EndpointOutput.MappedPair(io2, _)) =>
            compareTransputs(io1, io2)
          // pairs
          case (EndpointIO.Pair(left1, right1, _, _), EndpointIO.Pair(left2, right2, _, _)) =>
            compareTransputs(left1, left2) && compareTransputs(right1, right2)
          case (EndpointOutput.Pair(left1, right1, _, _), EndpointOutput.Pair(left2, right2, _, _)) =>
            compareTransputs(left1, left2) && compareTransputs(right1, right2)
          case (EndpointOutput.Pair(left1, right1, _, _), EndpointIO.Pair(left2, right2, _, _)) =>
            compareTransputs(left1, left2) && compareTransputs(right1, right2)
          case (EndpointIO.Pair(left1, right1, _, _), EndpointOutput.Pair(left2, right2, _, _)) =>
            compareTransputs(left1, left2) && compareTransputs(right1, right2)
          case (_, _) =>
            false
        }
    }
}

class JsonCodecMock[T] extends Codec[String, T, CodecFormat.Json] {

  override def rawDecode(l: String): DecodeResult[T] = ???

  override def encode(h: T): String = ???

  override def schema: Schema[T] = ???

  override def format: CodecFormat.Json = ???
}
