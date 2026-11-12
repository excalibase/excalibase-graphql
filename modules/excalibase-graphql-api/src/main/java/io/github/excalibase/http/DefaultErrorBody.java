package io.github.excalibase.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** The body Spring Boot gave every error no handler answered: timestamp, status, reason phrase and path. */
public final class DefaultErrorBody {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private DefaultErrorBody() {
    }

    public static Map<String, Object> of(HttpRequest<?> request, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", TIMESTAMP.format(OffsetDateTime.now(ZoneOffset.UTC)));
        body.put("status", status.getCode());
        body.put("error", status.getReason());
        body.put("path", RequestTarget.rawPath(request));
        return body;
    }

    public static MutableHttpResponse<Map<String, Object>> response(HttpRequest<?> request, HttpStatus status) {
        return HttpResponse.<Map<String, Object>>status(status)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .body(of(request, status));
    }
}
