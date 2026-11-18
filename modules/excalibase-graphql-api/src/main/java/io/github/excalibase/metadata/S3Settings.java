package io.github.excalibase.metadata;

import java.time.Duration;

/**
 * What the {@code s3} source is configured with.
 *
 * @param endpoint  an S3-compatible endpoint (MinIO, R2, a gateway); blank means AWS S3 in {@code region}
 * @param pathStyle {@code true} puts the bucket in the path, {@code false} in the host name; null picks path style
 *                  for a custom endpoint and virtual-hosted style for AWS
 * @param allowInsecure accept plain http to a host outside this machine or cluster
 */
public record S3Settings(String endpoint, String bucket, String key, String region, Boolean pathStyle,
                         boolean allowInsecure, Credentials credentials, Duration pollInterval,
                         Duration connectTimeout, Duration readTimeout) {

    public static final S3Settings NONE = new S3Settings("", "", "", "us-east-1", null, false, Credentials.NONE,
            Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(10));

    @Override
    public String toString() {
        return "S3Settings[endpoint, bucket, key and credentials withheld]";
    }

    /**
     * Where the access key pair (and an optional session token) come from: a literal, or a file that is re-read
     * on every request so a rotation needs no restart. A literal wins over a file.
     */
    public record Credentials(String accessKeyId, String secretAccessKey, String sessionToken,
                              String accessKeyIdFile, String secretAccessKeyFile, String sessionTokenFile) {

        public static final Credentials NONE = new Credentials("", "", "", "", "", "");

        public static Credentials literal(String accessKeyId, String secretAccessKey) {
            return new Credentials(accessKeyId, secretAccessKey, "", "", "", "");
        }

        /** Whether both halves of the key pair have a source; the session token is optional. */
        public boolean configured() {
            return (has(accessKeyId) || has(accessKeyIdFile)) && (has(secretAccessKey) || has(secretAccessKeyFile));
        }

        private static boolean has(String value) {
            return value != null && !value.isBlank();
        }

        @Override
        public String toString() {
            return "Credentials[withheld]";
        }
    }
}
