package io.github.excalibase.metadata;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 for one S3 request that has no query string: the headers to add so the store accepts
 * it. The URI's raw path must already be in canonical form (see {@link S3Location}); that is what is signed.
 */
final class SigV4 {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private SigV4() {
    }

    static Map<String, String> sign(String method, URI uri, String payloadSha256, S3Credentials.Resolved credentials,
                                    String region, Instant now) {
        return sign(method, uri, payloadSha256, credentials, region, now, new TreeMap<>());
    }

    /**
     * @param extraHeaders lower-case names and values of further headers that are sent and so must be signed
     * @return the headers to send: {@code x-amz-date}, {@code x-amz-content-sha256}, the session token when there is
     *         one, and {@code Authorization}. {@code Host} is sent by the HTTP client from the same URI.
     */
    static Map<String, String> sign(String method, URI uri, String payloadSha256, S3Credentials.Resolved credentials,
                                    String region, Instant now, TreeMap<String, String> extraHeaders) {
        String stamp = STAMP.format(now);
        String day = DAY.format(now);
        TreeMap<String, String> signed = new TreeMap<>(extraHeaders);
        signed.put("host", hostHeader(uri));
        signed.put("x-amz-content-sha256", payloadSha256);
        signed.put("x-amz-date", stamp);
        if (!credentials.sessionToken().isEmpty()) {
            signed.put("x-amz-security-token", credentials.sessionToken());
        }
        String names = String.join(";", signed.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value.trim()).append('\n'));
        String canonical = method + "\n" + uri.getRawPath() + "\n\n" + canonicalHeaders + "\n" + names + "\n"
                + payloadSha256;
        String scope = day + "/" + region + "/s3/aws4_request";
        String toSign = ALGORITHM + "\n" + stamp + "\n" + scope + "\n" + sha256Hex(canonical);
        String signature = HexFormat.of().formatHex(hmac(signingKey(credentials.secretAccessKey(), day, region),
                toSign));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-amz-date", stamp);
        headers.put("x-amz-content-sha256", payloadSha256);
        if (!credentials.sessionToken().isEmpty()) {
            headers.put("x-amz-security-token", credentials.sessionToken());
        }
        headers.put("Authorization", ALGORITHM + " Credential=" + credentials.accessKeyId() + "/" + scope
                + ",SignedHeaders=" + names + ",Signature=" + signature);
        return headers;
    }

    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean standard = port == -1 || (port == 80 && "http".equalsIgnoreCase(uri.getScheme()))
                || (port == 443 && "https".equalsIgnoreCase(uri.getScheme()));
        return uri.getHost().toLowerCase(Locale.ROOT) + (standard ? "" : ":" + port);
    }

    private static byte[] signingKey(String secret, String day, String region) {
        byte[] date = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), day);
        byte[] regionKey = hmac(date, region);
        byte[] service = hmac(regionKey, "s3");
        return hmac(service, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
