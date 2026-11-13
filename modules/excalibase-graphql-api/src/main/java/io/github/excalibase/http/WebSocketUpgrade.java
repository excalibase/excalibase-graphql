package io.github.excalibase.http;

import io.micronaut.http.HttpRequest;
import java.util.Locale;

/** Whether a request asks for a WebSocket: any value, of any Upgrade header, naming it, as the server reads it. */
public final class WebSocketUpgrade {

    private WebSocketUpgrade() {
    }

    public static boolean isRequested(HttpRequest<?> request) {
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
