package io.github.excalibase.config.ws;

import io.micronaut.http.HttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * The address a WebSocket client is counted under. Behind the edge proxy every peer is the proxy, so the last
 * {@code X-Forwarded-For} entry (the one the proxy appended) is used, but only when the peer is on a private
 * network: a client that reaches the engine directly cannot pick its own address.
 */
public final class ClientAddress {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_LENGTH = 64;

    private ClientAddress() {
    }

    static String of(HttpRequest<?> request) {
        InetSocketAddress remote = request.getRemoteAddress();
        InetAddress peer = remote == null ? null : remote.getAddress();
        if (peer != null && isInternal(peer)) {
            String forwarded = lastForwarded(request.getHeaders().get(FORWARDED_FOR));
            if (forwarded != null) {
                return forwarded;
            }
        }
        return peer == null ? "unknown" : peer.getHostAddress();
    }

    public static String lastForwarded(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String[] entries = header.split(",");
        String last = entries[entries.length - 1].trim();
        return last.isEmpty() || last.length() > MAX_LENGTH ? null : last;
    }

    public static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
