package sttp.tapir.server.akkahttp

import akka.http.scaladsl.model.{MediaType => _}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.RouteDirectives
import com.github.ghik.silencer.silent
import sttp.tapir._
import sttp.tapir.server.{ServerDefaults, ServerEndpoint}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

class EndpointToAkkaServer(serverOptions: AkkaHttpServerOptions) {
  /**
   * Converts the endpoint to a directive that -for matching requests- decodes the input parameters
   * and provides those input parameters and a function. The function can be called to complete the request.
   *
   * Example usage:
   * {{{
   * def logic(input: I): Future[Either[E, O] = ???
   *
   * endpoint.toDirectiveIC { (input, completion) =>
   *   securityDirective {
   *     completion(logic(input))
   *   }
   * }
   * }}}
   *
   * If type `I` is a tuple, and `logic` has 1 parameter per tuple member, use {{{completion((logic _).tupled(input))}}}
   */
  def toDirective[I, E, O](e: Endpoint[I, E, O, AkkaStream]): Directive[(I, Future[Either[E, O]] => Route)] = {
    toDirective1(e).flatMap { (values: I) =>
      extractLog.flatMap { log =>
        val completion: Future[Either[E, O]] => Route = result => onComplete(result) {
          case Success(Left(v))  => OutputToAkkaRoute(ServerDefaults.StatusCodes.error.code, endpoint.errorOutput, v)
          case Success(Right(v)) => OutputToAkkaRoute(ServerDefaults.StatusCodes.success.code, endpoint.output, v)
          case Failure(t) =>
            serverOptions.logRequestHandling.logicException(e, t)(log)
            throw t
        }
        tprovide((values, completion))
      }
    }
  }

  @silent("never used")
  def toRouteRecoverErrors[I, E, O](
      e: Endpoint[I, E, O, AkkaStream]
  )(logic: I => Future[O])(implicit eIsThrowable: E <:< Throwable, eClassTag: ClassTag[E]): Route = {
    def reifyFailedFuture(f: Future[O]): Future[Either[E, O]] = {
      import ExecutionContext.Implicits.global
      f.map(Right(_): Either[E, O]).recover {
        case e: Throwable if implicitly[ClassTag[E]].runtimeClass.isInstance(e) => Left(e.asInstanceOf[E]): Either[E, O]
      }
    }

    toRoute(e.serverLogic(logic.andThen(reifyFailedFuture)))
  }

  def toRoute[I, E, O](se: ServerEndpoint[I, E, O, AkkaStream, Future]): Route = {
    toDirective1(se.endpoint) { values =>
      extractLog { log =>
        mapResponse(resp => { serverOptions.logRequestHandling.requestHandled(se.endpoint, resp.status.intValue())(log); resp }) {
          onComplete(se.logic(values)) {
            case Success(Left(v))  => OutputToAkkaRoute(ServerDefaults.StatusCodes.error.code, se.endpoint.errorOutput, v)
            case Success(Right(v)) => OutputToAkkaRoute(ServerDefaults.StatusCodes.success.code, se.endpoint.output, v)
            case Failure(e) =>
              serverOptions.logRequestHandling.logicException(se.endpoint, e)(log)
              throw e
          }
        }
      }
    }
  }

  def toRoute(serverEndpoints: List[ServerEndpoint[_, _, _, AkkaStream, Future]]): Route = {
    serverEndpoints.map(se => toRoute(se)).foldLeft(RouteDirectives.reject: Route)(_ ~ _)
  }

  private def toDirective1[I, E, O](e: Endpoint[I, E, O, AkkaStream]): Directive1[I] = new EndpointToAkkaDirective(serverOptions)(e)
}
