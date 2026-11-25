package io.github.excalibase.metadata;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

/** A real MinIO, and the few signed calls a test needs to put documents in it. The engine code signs them too. */
final class MinioFixture implements AutoCloseable {

    static final String ACCESS_KEY = "test-access-key";
    static final String SECRET_KEY = "test-secret-key-0123456789-abcdef";
    static final String REGION = "us-east-1";

    private final GenericContainer<?> container = new GenericContainer<>("pgsty/minio:RELEASE.2026-08-04T00-00-00Z"
            + "@sha256:b6bfe7239bfc83fb90d31612d9704d86039dd714f7904b3f1ad68f211e602372")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY).withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    private final HttpClient http = HttpClient.newHttpClient();

    MinioFixture start() {
        container.start();
        return this;
    }

    String host() {
        return container.getHost();
    }

    int port() {
        return container.getMappedPort(9000);
    }

    String endpoint() {
        return "http://" + host() + ":" + port();
    }

    void createBucket(String bucket) {
        call("PUT", "/" + bucket, "");
    }

    void put(String bucket, String key, String body) {
        call("PUT", "/" + bucket + "/" + encoded(key), body);
    }

    void delete(String bucket, String key) {
        call("DELETE", "/" + bucket + "/" + encoded(key), "");
    }

    /** Encoded here on its own, not with the engine's code, so a mistake in one shows as a mismatch. */
    private static String encoded(String key) {
        return Arrays.stream(key.split("/", -1))
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
                        .replace("%7E", "~"))
                .collect(Collectors.joining("/"));
    }

    private void call(String method, String path, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create(endpoint() + path);
        Map<String, String> signed = SigV4.sign(method, uri, hex(payload),
                new S3Credentials.Resolved(ACCESS_KEY, SECRET_KEY, ""), REGION, Instant.now());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        signed.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(method + " " + path + " answered " + response.statusCode() + " "
                        + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        container.stop();
    }
}
