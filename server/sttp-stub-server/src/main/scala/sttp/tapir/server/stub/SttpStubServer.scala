package sttp.tapir.server.stub

import sttp.client.testing.SttpBackendStub
import sttp.model.StatusCode
import sttp.tapir.internal.SeqToParams
import sttp.tapir.server.internal.{DecodeInputs, DecodeInputsResult, InputValues}
import sttp.tapir.{DecodeFailure, Endpoint}

trait SttpStubServer {

  implicit class RichSttpBackendStub[F[_], S](val stub: SttpBackendStub[F, S]) {
    def whenRequestMatches[E, O](endpoint: Endpoint[_, E, O, _]): TypeAwareWhenRequest[E, O] = {
      new TypeAwareWhenRequest(
        new stub.WhenRequest(req =>
          DecodeInputs(endpoint.input, new SttpDecodeInputs(req)) match {
            case DecodeInputsResult.Failure(_, _) => false
            case DecodeInputsResult.Values(_, _)  => true
          }
        )
      )
    }

    def whenInputMatches[I, E, O](endpoint: Endpoint[I, E, O, _])(inputMatcher: I => Boolean): TypeAwareWhenRequest[E, O] = {
      new TypeAwareWhenRequest(
        new stub.WhenRequest(req =>
          DecodeInputs(endpoint.input, new SttpDecodeInputs(req)) match {
            case DecodeInputsResult.Failure(_, _)  => false
            case values: DecodeInputsResult.Values => inputMatcher(SeqToParams(InputValues(endpoint.input, values)).asInstanceOf[I])
          }
        )
      )
    }

    def whenDecodingInputFailureMatches[E, O](
        endpoint: Endpoint[_, E, O, _]
    )(failureMatcher: PartialFunction[DecodeFailure, Boolean]): TypeAwareWhenRequest[E, O] = {
      new TypeAwareWhenRequest(
        new stub.WhenRequest(req => {
          val result = DecodeInputs(endpoint.input, new SttpDecodeInputs(req))
          result match {
            case DecodeInputsResult.Failure(_, f) if failureMatcher.isDefinedAt(f) => failureMatcher(f)
            case DecodeInputsResult.Values(_, _)                                   => false
          }
        })
      )
    }

    def whenDecodingInputFailure[E, O](endpoint: Endpoint[_, E, O, _]): TypeAwareWhenRequest[E, O] = {
      whenDecodingInputFailureMatches(endpoint) { case _ => true }
    }

    class TypeAwareWhenRequest[E, O](whenRequest: stub.WhenRequest) {

      def thenSuccess(response: O): SttpBackendStub[F, S] =
        whenRequest.thenRespond(response)

      def thenError(errorResponse: E, statusCode: StatusCode): SttpBackendStub[F, S] =
        whenRequest.thenRespond(sttp.client.Response[E](errorResponse, statusCode))

      def genericResponse: stub.WhenRequest = whenRequest
    }
  }
}
