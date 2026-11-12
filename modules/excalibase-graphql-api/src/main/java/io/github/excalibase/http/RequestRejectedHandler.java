package io.github.excalibase.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

/** A refused target is a 400 with the default error body. */
@Singleton
public class RequestRejectedHandler implements ExceptionHandler<RequestRejectedException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, RequestRejectedException exception) {
        return DefaultErrorBody.response(request, HttpStatus.BAD_REQUEST);
    }
}
