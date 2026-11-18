package io.github.excalibase.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Reads the key pair for one request. Nothing here puts a value, or a file name, into a message. */
public final class S3Credentials {

    private S3Credentials() {
    }

    /** One request's keys; the secret and token never appear in {@code toString}. */
    record Resolved(String accessKeyId, String secretAccessKey, String sessionToken) {
        @Override
        public String toString() {
            return "Resolved[withheld]";
        }
    }

    static Resolved load(S3Settings.Credentials source) throws IOException {
        String id = value(source.accessKeyId(), source.accessKeyIdFile());
        String secret = value(source.secretAccessKey(), source.secretAccessKeyFile());
        if (id.isEmpty() || secret.isEmpty()) {
            throw new IOException("no access key is available: set metadata.s3.access-key-id and "
                    + "metadata.s3.secret-access-key (or AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY)");
        }
        return new Resolved(id, secret, value(source.sessionToken(), source.sessionTokenFile()));
    }

    private static String value(String literal, String file) throws IOException {
        if (literal != null && !literal.isBlank()) {
            return literal.trim();
        }
        if (file == null || file.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException _) {
            throw new IOException("a credentials file cannot be read");
        }
    }

    /**
     * The engine's own settings first, then the standard AWS variable names (and a {@code _FILE} variant of each
     * for mounted secrets). Web-identity (IRSA) and instance-profile credentials are not supported.
     */
    public static S3Settings.Credentials fromEnvironment(Map<String, String> env, String accessKeyId, String secretAccessKey,
                                                  String sessionToken, String accessKeyIdFile,
                                                  String secretAccessKeyFile, String sessionTokenFile) {
        return new S3Settings.Credentials(
                first(accessKeyId, env.get("AWS_ACCESS_KEY_ID")),
                first(secretAccessKey, env.get("AWS_SECRET_ACCESS_KEY")),
                first(sessionToken, env.get("AWS_SESSION_TOKEN")),
                first(accessKeyIdFile, env.get("AWS_ACCESS_KEY_ID_FILE")),
                first(secretAccessKeyFile, env.get("AWS_SECRET_ACCESS_KEY_FILE")),
                first(sessionTokenFile, env.get("AWS_SESSION_TOKEN_FILE")));
    }

    private static String first(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }
}
