package io.github.excalibase.metadata;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Works out the one object URL the {@code s3} source reads, once at start, and refuses what cannot be used. The
 * path it returns is in the canonical form SigV4 signs (each key segment percent-encoded once), and a default port
 * is dropped so the signed host is the host the HTTP client sends. A message never repeats a configured value.
 */
final class S3Location {

    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern REGION = Pattern.compile("[a-z0-9-]{1,40}");

    private S3Location() {
    }

    /** @return the object's URL; @throws IllegalStateException when the settings are unusable */
    static URI require(S3Settings settings, String projectId) {
        String bucket = text(settings.bucket());
        if (!BUCKET.matcher(bucket).matches() || bucket.contains("..")) {
            throw new IllegalStateException("metadata.s3.bucket is required with metadata.source=s3 and must be a "
                    + "valid bucket name (3 to 63 lower-case letters, digits, dots or hyphens)");
        }
        String region = text(settings.region());
        if (!REGION.matcher(region).matches()) {
            throw new IllegalStateException("metadata.s3.region is required (for example us-east-1; R2 uses auto)");
        }
        String path = encodedKey(text(settings.key()).replace("{projectId}", projectId == null ? "" : projectId));
        URI endpoint = endpoint(settings, region);
        boolean pathStyle = settings.pathStyle() != null ? settings.pathStyle() : !text(settings.endpoint()).isEmpty();
        String authority = endpoint.getPort() == -1 ? endpoint.getHost() : endpoint.getHost() + ":" + endpoint.getPort();
        String text = pathStyle ? endpoint.getScheme() + "://" + authority + "/" + bucket + path
                : endpoint.getScheme() + "://" + bucket + "." + authority + path;
        try {
            return new URI(text);
        } catch (URISyntaxException _) {
            throw new IllegalStateException("metadata.s3.endpoint, bucket and key do not make a valid URL");
        }
    }

    private static URI endpoint(S3Settings settings, String region) {
        String configured = text(settings.endpoint());
        URI uri = parse(configured.isEmpty() ? "https://s3." + region + ".amazonaws.com" : configured);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalStateException("metadata.s3.endpoint must be an http or https URL");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalStateException("metadata.s3.endpoint needs a host and no user:password in it");
        }
        String path = uri.getRawPath();
        if ((path != null && !path.isEmpty() && !path.equals("/")) || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException("metadata.s3.endpoint must be a scheme, host and optional port with no path");
        }
        if (scheme.equals("http") && !settings.allowInsecure() && !UrlLocation.isLocalOrClusterInternal(uri.getHost())) {
            throw new IllegalStateException("metadata.s3.endpoint is plain http to a host outside this machine or "
                    + "cluster: use https, or set metadata.s3.allow-insecure=true to accept it");
        }
        return withoutDefaultPort(uri, scheme);
    }

    private static URI withoutDefaultPort(URI uri, String scheme) {
        int port = uri.getPort();
        boolean standard = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return parse(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 || standard ? "" : ":" + port));
    }

    private static URI parse(String text) {
        try {
            return new URI(text);
        } catch (URISyntaxException _) {
            throw new IllegalStateException("metadata.s3.endpoint is not a valid URL");
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** {@code /seg/seg}: every segment encoded once, as S3's canonical URI requires. */
    private static String encodedKey(String key) {
        String trimmed = key.startsWith("/") ? key.substring(1) : key;
        StringBuilder path = new StringBuilder();
        for (String segment : trimmed.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalStateException("metadata.s3.key is required and must be an object name with no "
                        + "empty, . or .. parts");
            }
            path.append('/').append(encode(segment));
        }
        return path.toString();
    }

    private static String encode(String segment) {
        StringBuilder out = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }
}
