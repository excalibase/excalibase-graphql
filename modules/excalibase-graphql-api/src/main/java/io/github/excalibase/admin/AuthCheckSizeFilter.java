package io.github.excalibase.admin;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.Map;

/**
 * Refuses a pasted token whose declared size is over the cap before the body is read. The check in the controller runs
 * only after the server has buffered the whole body (up to its general limit), so a caller could make it hold megabytes
 * per request; this one answers from the headers. A body sent without a length is still caught by the controller.
 */
@ServerFilter(AuthCheckSizeFilter.PATH)
@Requires(property = "excalibase.console.enabled", pattern = AdminSettings.ON_PATTERN)
@Requires(property = "console.auth-page.enabled", pattern = AdminSettings.ON_PATTERN)
public class AuthCheckSizeFilter implements Ordered {

    static final String PATH = "/_admin/v1/auth/check";

    /** Right behind the admin port filter (-945), so only a caller holding the admin secret gets this far. */
    private static final int ORDER = -944;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<Object> filter(HttpRequest<?> request) {
        if (request.getMethod() == HttpMethod.POST && request.getContentLength() > TokenCheck.MAX_BYTES) {
            return HttpResponse.status(HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .body(Map.of("error", "token_too_large"));
        }
        return null;
    }
}
