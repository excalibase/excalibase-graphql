package io.github.excalibase.cors;

import io.github.excalibase.config.ws.ClientAddress;
import io.micronaut.http.HttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;

/**
 * The scheme and host this server was addressed by. Behind the edge proxy the connection is plain http to the
 * proxy, so {@code X-Forwarded-Proto} and {@code X-Forwarded-Host} (the last entry, the one the proxy appended)
 * are honoured, but only from a private-network peer, the trust rule the client address uses.
 */
final class ForwardedOrigin {

    private ForwardedOrigin() {
    }

    static String scheme(HttpRequest<?> request) {
        if (trusted(request)) {
            String forwarded = ClientAddress.lastForwarded(request.getHeaders().get("X-Forwarded-Proto"));
            if (forwarded != null) {
                String scheme = forwarded.toLowerCase(Locale.ROOT);
                if (scheme.equals("http") || scheme.equals("https")) {
                    return scheme;
                }
            }
        }
        return request.isSecure() ? "https" : "http";
    }

    static String host(HttpRequest<?> request) {
        if (trusted(request)) {
            String forwarded = ClientAddress.lastForwarded(request.getHeaders().get("X-Forwarded-Host"));
            if (forwarded != null) {
                return forwarded;
            }
        }
        return request.getHeaders().get("Host");
    }

    private static boolean trusted(HttpRequest<?> request) {
        InetSocketAddress remote = request.getRemoteAddress();
        InetAddress peer = remote == null ? null : remote.getAddress();
        return peer != null && ClientAddress.isInternal(peer);
    }
}
