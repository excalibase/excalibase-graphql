package io.github.excalibase.metadata;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Checks where the {@code url} source fetches from, once at start. The message never repeats the value: a
 * mistyped location can carry credentials.
 */
final class UrlLocation {

    private static final String SETTING = "metadata.url.location";

    private UrlLocation() {
    }

    /** @param location the configured value; {@code {projectId}} is replaced by the project id */
    static URI require(String location, String projectId, boolean allowInsecure) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException(SETTING + " is required with metadata.source=url");
        }
        URI uri = parse(location.trim().replace("{projectId}", projectId == null ? "" : projectId));
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalStateException(SETTING + " must be an http or https URL");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalStateException(SETTING + " needs a host and no user:password in it (use the token setting)");
        }
        if (scheme.equals("http") && !allowInsecure && !isLocalOrClusterInternal(uri.getHost())) {
            throw new IllegalStateException(SETTING + " is plain http to a host outside this machine or cluster: "
                    + "use https, or set metadata.url.allow-insecure=true to accept it");
        }
        return uri;
    }

    private static URI parse(String text) {
        try {
            return new URI(text);
        } catch (URISyntaxException _) {
            throw new IllegalStateException(SETTING + " is not a valid URL");
        }
    }

    /**
     * Loopback, and names that only resolve inside a cluster or a compose network: {@code *.svc},
     * {@code *.cluster.local} and a bare single-label service name.
     */
    static boolean isLocalOrClusterInternal(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        return name.equals("localhost") || name.endsWith(".localhost") || name.equals("[::1]")
                || name.startsWith("127.") || name.endsWith(".svc") || name.endsWith(".cluster.local")
                || (!name.contains(".") && !name.contains(":"));
    }
}
