package io.github.excalibase.cors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;
import java.util.List;

/**
 * CORS where the browser is the enforcer. A preflight from an unlisted origin
 * is refused (403, no CORS headers), as Spring does. An actual request is
 * always served; only a listed origin gets {@code Access-Control-Allow-Origin},
 * so a browser page from any other origin cannot read the response.
 *
 * <p>Refusing an actual request for its {@code Origin} alone adds nothing on
 * this API: auth is a bearer token, never a cookie, so a foreign page has no
 * ambient credential to ride, and any non-browser caller can omit or forge the
 * header. It does break native apps (Capacitor, Tauri, Electron file pages
 * sending {@code null}) and server-side proxies that forward a browser Origin.
 */
public class BrowserEnforcedCorsProcessor extends DefaultCorsProcessor {

    @Override
    protected boolean handleInternal(ServerHttpRequest request, ServerHttpResponse response,
                                     CorsConfiguration config, boolean preFlightRequest) throws IOException {
        if (preFlightRequest) {
            return super.handleInternal(request, response, config, true);
        }
        String allowOrigin = checkOrigin(config, request.getHeaders().getOrigin());
        if (allowOrigin != null) {
            grant(response.getHeaders(), config, allowOrigin);
        }
        response.flush();
        return true;
    }

    private static void grant(HttpHeaders headers, CorsConfiguration config, String allowOrigin) {
        headers.setAccessControlAllowOrigin(allowOrigin);
        List<String> exposed = config.getExposedHeaders();
        if (exposed != null && !exposed.isEmpty()) {
            headers.setAccessControlExposeHeaders(exposed);
        }
        if (Boolean.TRUE.equals(config.getAllowCredentials())) {
            headers.setAccessControlAllowCredentials(true);
        }
    }
}
