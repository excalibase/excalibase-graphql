package io.github.excalibase.http;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;

/** Builders for an {@code HttpResponse<Object>}: the one body type every endpoint answers with. */
public final class Reply {

    private Reply() {
    }

    public static MutableHttpResponse<Object> ok() {
        return HttpResponse.ok();
    }

    public static MutableHttpResponse<Object> ok(Object body) {
        return HttpResponse.ok().body(body);
    }

    public static MutableHttpResponse<Object> status(HttpStatus status) {
        return HttpResponse.status(status);
    }

    public static MutableHttpResponse<Object> status(int status) {
        return HttpResponse.status(HttpStatus.valueOf(status));
    }

    public static MutableHttpResponse<Object> badRequest() {
        return HttpResponse.badRequest();
    }

    /** The same answer with one more header. */
    public static MutableHttpResponse<Object> withHeader(HttpResponse<Object> response, String name, String value) {
        return ((MutableHttpResponse<Object>) response).header(name, value);
    }
}
