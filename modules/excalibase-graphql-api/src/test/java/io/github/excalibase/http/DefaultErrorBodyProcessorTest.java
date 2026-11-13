package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.observability.HttpRequestFacts;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultErrorBodyProcessorTest {

    private final DefaultErrorBodyProcessor processor = new DefaultErrorBodyProcessor();

    @Test
    @DisplayName("the exception behind an error response stays on the request for its metric and span")
    void exceptionIsKept() {
        MutableHttpRequest<?> request = HttpRequest.GET("/proj/api/v1/rpc/a.b.c");
        IllegalStateException cause = new IllegalStateException("Invalid function name");

        processor.processResponse(ErrorContext.builder(request).cause(cause).build(), HttpResponse.serverError());

        assertThat(request.getAttribute(HttpRequestFacts.EXCEPTION_ATTRIBUTE, Throwable.class)).containsSame(cause);
    }

    @Test
    @DisplayName("an error with no exception behind it, a missing route, leaves nothing on the request")
    void noExceptionNothingKept() {
        MutableHttpRequest<?> request = HttpRequest.GET("/proj/nothing");

        processor.processResponse(ErrorContext.builder(request).build(), HttpResponse.status(HttpStatus.NOT_FOUND));

        assertThat(request.getAttribute(HttpRequestFacts.EXCEPTION_ATTRIBUTE)).isEmpty();
    }
}
