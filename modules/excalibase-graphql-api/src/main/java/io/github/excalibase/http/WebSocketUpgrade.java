package io.github.excalibase.http;

import io.micronaut.http.HttpRequest;
import java.util.List;
import java.util.Locale;

/**
 * Whether a request asks for a WebSocket. The servlet stack read the first Upgrade header and nothing else: that
 * value being websocket is an upgrade, anything else (a list, a second header) is a plain request. The framework
 * here upgrades when any value says websocket, so a request the old stack saw as plain but this one would upgrade
 * is {@link #isDisguised}, and is answered as the plain request it was.
 */
public final class WebSocketUpgrade {

    private WebSocketUpgrade() {
    }

    /** The first Upgrade header is {@code websocket}: the handshake checks apply. */
    public static boolean isRequested(HttpRequest<?> request) {
        List<String> values = request.getHeaders().getAll("Upgrade");
        return !values.isEmpty() && "websocket".equalsIgnoreCase(values.getFirst().strip());
    }

    /** Some Upgrade value names websocket although the first header does not say it alone. */
    public static boolean isDisguised(HttpRequest<?> request) {
        if (isRequested(request)) {
            return false;
        }
        for (String line : request.getHeaders().getAll("Upgrade")) {
            for (String protocol : line.split(",")) {
                if (protocol.strip().toLowerCase(Locale.ROOT).equals("websocket")) {
                    return true;
                }
            }
        }
        return false;
    }
}
