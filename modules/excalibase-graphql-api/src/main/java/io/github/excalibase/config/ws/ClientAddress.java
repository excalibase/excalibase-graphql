package io.github.excalibase.config.ws;

import io.micronaut.http.HttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;

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

    public static String of(HttpRequest<?> request) {
        InetAddress peer = Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress).orElse(null);
        if (peer != null && isInternal(peer)) {
            String forwarded = lastForwarded(request.getHeaders().getAll(FORWARDED_FOR));
            if (forwarded != null) {
                return byPrefix(forwarded);
            }
        }
        return peer == null ? "unknown" : byPrefix(peer.getHostAddress());
    }

    /**
     * An IPv6 client is counted by its /64, the smallest block one subscriber is given: a host can pick any
     * address inside it, so counting each address would let it take as many sockets as it likes.
     */
    public static String byPrefix(String address) {
        if (address.indexOf(':') < 0) {
            return address;
        }
        try {
            byte[] bytes = InetAddress.getByName(address).getAddress();
            if (bytes.length != 16) {
                return address;
            }
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                prefix.append(String.format("%02x", bytes[i] & 0xff)).append(i % 2 == 1 && i < 7 ? ":" : "");
            }
            return prefix.append("::/64").toString();
        } catch (UnknownHostException _) {
            return address;
        }
    }

    /** The last entry of the last header line: a proxy adds its own line, and the first line is the client's. */
    public static String lastForwarded(List<String> lines) {
        return lines == null || lines.isEmpty() ? null : lastForwarded(lines.get(lines.size() - 1));
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
