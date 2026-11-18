package io.github.excalibase.config;

import io.github.excalibase.metadata.S3Credentials;
import io.github.excalibase.metadata.S3Settings;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.time.Duration;

/**
 * {@code metadata.s3.*} for the {@code s3} metadata source. The key pair is also read from the standard AWS
 * variable names when the engine's own settings are blank.
 */
@Factory
class S3MetadataConfig {

    @Singleton
    S3Settings s3Settings(
            @Value("${metadata.s3.endpoint:}") String endpoint,
            @Value("${metadata.s3.bucket:}") String bucket,
            @Value("${metadata.s3.key:}") String key,
            @Value("${metadata.s3.region:us-east-1}") String region,
            @Value("${metadata.s3.path-style:}") String pathStyle,
            @Value("${metadata.s3.allow-insecure:false}") boolean allowInsecure,
            @Value("${metadata.s3.access-key-id:}") String accessKeyId,
            @Value("${metadata.s3.secret-access-key:}") String secretAccessKey,
            @Value("${metadata.s3.session-token:}") String sessionToken,
            @Value("${metadata.s3.access-key-id-file:}") String accessKeyIdFile,
            @Value("${metadata.s3.secret-access-key-file:}") String secretAccessKeyFile,
            @Value("${metadata.s3.session-token-file:}") String sessionTokenFile,
            @Value("${metadata.s3.poll-interval-ms:5000}") long pollIntervalMs,
            @Value("${metadata.s3.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${metadata.s3.read-timeout-ms:10000}") long readTimeoutMs) {
        return new S3Settings(endpoint, bucket, key, region, pathStyle.isBlank() ? null : Boolean.valueOf(pathStyle),
                allowInsecure,
                S3Credentials.fromEnvironment(System.getenv(), accessKeyId, secretAccessKey, sessionToken,
                        accessKeyIdFile, secretAccessKeyFile, sessionTokenFile),
                Duration.ofMillis(pollIntervalMs), Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs));
    }
}
