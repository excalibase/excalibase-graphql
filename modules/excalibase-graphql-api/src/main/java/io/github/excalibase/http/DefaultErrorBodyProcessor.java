package io.github.excalibase.http;

import io.github.excalibase.observability.HttpRequestFacts;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import io.micronaut.http.server.exceptions.response.HateoasErrorResponseProcessor;
import jakarta.inject.Singleton;

import java.util.Map;

/**
 * Renders every error the framework answers, a missing route to an unhandled exception, as {@link DefaultErrorBody},
 * and leaves the exception behind an error on the request so the request's metric and span name it.
 * A 404 the router answers is not an exception of the request.
 */
@Singleton
@Replaces(HateoasErrorResponseProcessor.class)
public class DefaultErrorBodyProcessor implements ErrorResponseProcessor<Map<String, Object>> {

    @Override
    @SuppressWarnings("unchecked")
    public MutableHttpResponse<Map<String, Object>> processResponse(ErrorContext context,
                                                                    MutableHttpResponse<?> response) {
        if (response.code() != HttpStatus.NOT_FOUND.getCode()) {
            context.getRootCause().ifPresent(cause -> context.getRequest().setAttribute(HttpRequestFacts.EXCEPTION_ATTRIBUTE, cause));
        }
        return ((MutableHttpResponse<Map<String, Object>>) response)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .body(DefaultErrorBody.of(context.getRequest(), response.status()));
    }
}
