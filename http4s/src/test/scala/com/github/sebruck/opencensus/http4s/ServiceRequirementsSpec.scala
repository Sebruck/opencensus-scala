package com.github.sebruck.opencensus.http4s

import cats.effect.IO
import com.github.sebruck.opencensus.Tracing
import com.github.sebruck.opencensus.http.testSuite.MockTracing
import io.opencensus.trace.Status
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpService, Method, Request, Uri}
import org.scalatest.{FlatSpec, Matchers, OptionValues}

import scala.util.Try

trait ServiceRequirementsSpec
    extends FlatSpec
    with Matchers
    with OptionValues
    with Http4sDsl[IO] {

  val path     = "/my/fancy/path"
  val uri: Uri = Uri.unsafeFromString(path)
  val request  = Request[IO](Method.GET, uri)

  def testService(
      successServiceFromMiddleware: TracingMiddleware => HttpService[IO],
      failingServiceFromMiddleware: TracingMiddleware => HttpService[IO],
      badRequestServiceFromMiddleware: TracingMiddleware => HttpService[IO],
      errorServiceFromMiddleware: TracingMiddleware => HttpService[IO]) = {

    it should "start a span with the path of the request" in {
      val (middleware, mockTracing) = middlewareWithMock()

      successServiceFromMiddleware(middleware)
        .orNotFound(request)
        .unsafeRunSync()
      mockTracing.startedSpans.headOption.value.name shouldBe path
    }

    it should "start a span without parent context when no span context was propagated" in {
      val (middleware, mockTracing) = middlewareWithMock()

      successServiceFromMiddleware(middleware)
        .orNotFound(request)
        .unsafeRunSync()
      mockTracing.startedSpans.headOption.value.parentContext shouldBe empty
    }

    it should "end a span with status OK when the route is successful" in {
      val (middleware, mockTracing) = middlewareWithMock()

      successServiceFromMiddleware(middleware)
        .orNotFound(request)
        .unsafeRunSync()
      mockTracing.endedSpansStatuses.headOption.value shouldBe Status.OK
    }

    it should "end a span with status INTERNAL when the route fails" in {
      val (middleware, mockTracing) = middlewareWithMock()
      Try(
        failingServiceFromMiddleware(middleware)
          .orNotFound(request)
          .unsafeRunSync())

      mockTracing.endedSpansStatuses.headOption.value shouldBe Status.INTERNAL
    }

    it should "end a span with status INTERNAL_ERROR when the route completes with an errornous status code" in {
      val (middleware, mockTracing) = middlewareWithMock()

      errorServiceFromMiddleware(middleware)
        .orNotFound(request)
        .unsafeRunSync()

      mockTracing.endedSpansStatuses.headOption.value shouldBe Status.INTERNAL
    }

    it should "end a span with status INVALID_ARGUMENT when the route completes with a BadRequest" in {
      val (middleware, mockTracing) = middlewareWithMock()

      badRequestServiceFromMiddleware(middleware)
        .orNotFound(request)
        .unsafeRunSync()

      mockTracing.endedSpansStatuses.headOption.value shouldBe Status.INVALID_ARGUMENT
    }


    it should "set the http attributes" in {
      import io.opencensus.trace.AttributeValue._
      val (middleware, mockTracing) = middlewareWithMock()

      successServiceFromMiddleware(middleware)
        .orNotFound(
          Request[IO](Method.GET,
                      Uri.unsafeFromString("http://example.com/my/fancy/path")))
        .unsafeRunSync()
      val attributes = mockTracing.startedSpans.headOption.value.attributes

      attributes.get("http.host").value shouldBe stringAttributeValue(
        "example.com")
      attributes.get("http.path").value shouldBe stringAttributeValue(
        "/my/fancy/path")
      attributes.get("http.method").value shouldBe stringAttributeValue("GET")
      attributes.get("http.status_code").value shouldBe longAttributeValue(200L)
    }

    // todo implement propagation
    ignore should "start a span with the propagated context as parent when a span context was propagated" in {
      val (middleware, mockTracing) = middlewareWithMock()

      successServiceFromMiddleware(middleware)
        .orNotFound(request)
        .unsafeRunSync()

      mockTracing.startedSpans.headOption.value.parentContext shouldBe defined
    }
  }

  def middlewareWithMock() = {
    val mockTracing = new MockTracing
    val middleware = new TracingMiddleware {
      override protected def tracing: Tracing = mockTracing
    }
    (middleware, mockTracing)
  }
}
