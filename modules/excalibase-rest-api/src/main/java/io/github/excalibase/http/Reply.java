package io.github.excalibase.http;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;

/** Builders for an {@code HttpResponse<Object>}: the one body type every endpoint answers with. */
public final class Reply {

    private Reply() {
    }

    public static MutableHttpResponse<Object> ok() {
        return json(HttpResponse.ok());
    }

    public static MutableHttpResponse<Object> ok(Object body) {
        return json(HttpResponse.ok()).body(body);
    }

    public static MutableHttpResponse<Object> status(HttpStatus status) {
        return json(HttpResponse.status(status));
    }

    public static MutableHttpResponse<Object> status(int status) {
        return status(HttpStatus.valueOf(status));
    }

    public static MutableHttpResponse<Object> badRequest() {
        return json(HttpResponse.badRequest());
    }

    /** JSON unless the handler names another type, whatever the request's Accept header was. */
    private static MutableHttpResponse<Object> json(MutableHttpResponse<Object> response) {
        return response.contentType(MediaType.APPLICATION_JSON_TYPE);
    }

    /** The same answer with one more header. */
    public static MutableHttpResponse<Object> withHeader(HttpResponse<Object> response, String name, String value) {
        return ((MutableHttpResponse<Object>) response).header(name, value);
    }
}
