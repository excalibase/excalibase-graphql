package io.github.excalibase.metadata;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks where the {@code url} source fetches from, once at start. The message never repeats the value: a
 * mistyped location can carry credentials.
 */
final class UrlLocation {

    private static final Pattern LOOPBACK_V4 = Pattern.compile("127(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
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
     * Loopback literals and the names Kubernetes DNS owns ({@code *.svc}, {@code *.cluster.local}). A bare
     * single-word name is not trusted: a search domain can resolve it outside, and a number such as
     * {@code 134744072} is an address. Those need {@code metadata.url.allow-insecure=true}.
     */
    static boolean isLocalOrClusterInternal(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        return name.equals("localhost") || name.equals("[::1]")
                || LOOPBACK_V4.matcher(name).matches() || name.endsWith(".svc") || name.endsWith(".cluster.local");
    }
}
