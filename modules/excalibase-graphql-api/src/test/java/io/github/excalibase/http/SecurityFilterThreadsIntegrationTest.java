package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.support.EngineIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The security filters read databases, so none of them may run on a Netty event loop thread, whatever the shape of
 * the traffic: keep-alive or closing connections, pipelined requests, a slow body, a connection admitted after a
 * held one went away. The thread is noted before the project check, between CORS and authentication, and after
 * authentication.
 */
class SecurityFilterThreadsIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String SECRET = "test-hmac-secret-0123456789abcdef0123456789";
    private static final AtomicInteger IDS = new AtomicInteger();

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent");
        properties.put("spring.datasource.username", "fake");
        properties.put("spring.datasource.password", "fake");
        properties.put("app.project-id", PROJECT);
        properties.put("app.database-type", "postgres");
        properties.put("app.nats.enabled", "false");
        properties.put("app.security.auth.hmac-secret", SECRET);
        properties.put("app.security.auth.require-aud", "false");
        properties.put("app.overload.max-connections-per-cpu", "1");
        properties.put("app.overload.accept-count-per-cpu", "256");
        // This test is about requests held for a slot: a long wait and a deep queue keep them from being refused.
        properties.put("app.overload.admission-wait-ms", "60000");
        properties.put("app.overload.wait-queue-per-cpu", "1000");
        properties.put("app.overload.keep-alive-timeout-ms", "60000");
        properties.put("endpoints.all.port", "-1");
        properties.put("micronaut.server.port", "-1");
        properties.put("test.record-security-threads", "true");
        return properties;
    }

    private static String token() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("42").claim("userId", 42L)
                .claim("projectId", PROJECT).claim("role", "service").claim("scope", "service")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Inject
    MeterRegistry meters;

    private String token;
    private final java.util.concurrent.ConcurrentLinkedQueue<String> statuses = new java.util.concurrent.ConcurrentLinkedQueue<>();

    @BeforeEach
    void fresh() throws Exception {
        awaitOpenConnections(0);
        SecurityFilterThreadsRecorder.THREADS.clear();
        token = token();
    }

    /** The connection bound the server itself reports, so the test does not guess it from the CPU count. */
    private int connectionBound() {
        return (int) meters.get("tomcat.connections.config.max").gauge().value();
    }

    private void awaitOpenConnections(int expected) throws InterruptedException {
        for (int i = 0; i < 200 && (int) meters.get("tomcat.connections.current").gauge().value() != expected; i++) {
            Thread.sleep(50);
        }
        assertThat((int) meters.get("tomcat.connections.current").gauge().value())
                .as("open connections the server counts").isEqualTo(expected);
    }

    private String probe() {
        return "p" + IDS.incrementAndGet();
    }

    private String get(String probe, boolean close) {
        return "GET /" + PROJECT + "/api/v1/customer HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + token
                + "\r\nX-Probe: " + probe + "\r\n" + (close ? "Connection: close\r\n" : "") + "\r\n";
    }

    private void drain(Socket socket) throws IOException {
        socket.setSoTimeout(10_000);
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[8192];
        boolean first = true;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (first) {
                statuses.add(new String(buffer, 0, Math.min(read, 60), StandardCharsets.ISO_8859_1));
                first = false;
            }
        }
    }

    private Map<String, Long> answers() {
        return statuses.stream().collect(java.util.stream.Collectors.groupingBy(line -> line, java.util.stream.Collectors.counting()));
    }

    private void firstLine(Socket socket) throws IOException {
        socket.setSoTimeout(10_000);
        byte[] buffer = new byte[12];
        int read = socket.getInputStream().readNBytes(buffer, 0, buffer.length);
        statuses.add(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
    }

    private void assertNoEventLoop(int expectedRequests) {
        Map<String, List<String>> seen = SecurityFilterThreadsRecorder.THREADS;
        assertThat(seen).as("requests recorded; answers " + answers()).hasSize(expectedRequests);
        List<String> onEventLoop = new ArrayList<>();
        seen.forEach((probe, points) -> {
            assertThat(points).as(probe + " answers " + answers()).hasSize(3);
            points.stream().filter(point -> point.toLowerCase().contains("eventloop"))
                    .forEach(point -> onEventLoop.add(probe + " " + point));
        });
        assertThat(onEventLoop).as("filter points that ran on an event loop thread").isEmpty();
    }

    @Test
    @DisplayName("300 concurrent requests, keep-alive and closing, run every security filter off the event loop")
    void concurrentRequests() throws Exception {
        int requests = 300;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                boolean close = i % 2 == 0;
                done.add(pool.submit(() -> {
                    try (Socket socket = new Socket("localhost", server.getPort())) {
                        socket.getOutputStream().write(get(probe(), close).getBytes(StandardCharsets.UTF_8));
                        if (close) {
                            drain(socket);
                        } else {
                            // a keep-alive connection stays open: leave once the answer has started
                            firstLine(socket);
                        }
                        return null;
                    }
                }));
            }
            for (Future<?> request : done) {
                request.get();
            }
        } finally {
            pool.shutdownNow();
        }
        awaitRecorded(requests);

        assertNoEventLoop(requests);
    }

    @Test
    @DisplayName("15 pipelined requests on one connection run every security filter off the event loop")
    void pipelinedRequests() throws Exception {
        StringBuilder batch = new StringBuilder();
        int count = 15;
        for (int i = 0; i < count; i++) {
            batch.append(get(probe(), i == count - 1));
        }
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.getOutputStream().write(batch.toString().getBytes(StandardCharsets.UTF_8));
            drain(socket);
        }
        awaitRecorded(count);

        assertNoEventLoop(count);
    }

    @Test
    @DisplayName("a POST whose body arrives slowly runs every security filter off the event loop")
    void slowBody() throws Exception {
        String probe = probe();
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.getOutputStream().write(("POST /" + PROJECT + "/api/v1/customer HTTP/1.1\r\nHost: localhost\r\n"
                    + "Authorization: Bearer " + token + "\r\nX-Probe: " + probe + "\r\nConnection: close\r\n"
                    + "Content-Type: application/json\r\nContent-Length: 4\r\n\r\n{\"").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            Thread.sleep(500);
            socket.getOutputStream().write("a}".getBytes(StandardCharsets.UTF_8));
            drain(socket);
        }
        awaitRecorded(1);

        assertNoEventLoop(1);
    }

    @Test
    @DisplayName("requests admitted when held connections go away run every security filter off the event loop")
    void admittedFromAHeldConnection() throws Exception {
        List<Socket> held = new ArrayList<>();
        List<Socket> waiting = new ArrayList<>();
        try {
            int bound = connectionBound();
            for (int i = 0; i < bound; i++) {
                Socket slot = new Socket("localhost", server.getPort());
                // half a request line: the server keeps waiting for the rest and the connection holds its slot
                slot.getOutputStream().write(("GET /" + PROJECT + "/api/v1/cus").getBytes(StandardCharsets.UTF_8));
                held.add(slot);
            }
            // every slot is taken before the next connection arrives, so arrival order cannot shuffle them
            awaitOpenConnections(bound);
            int extra = 20;
            for (int i = 0; i < extra; i++) {
                Socket socket = new Socket("localhost", server.getPort());
                socket.getOutputStream().write(get(probe(), true).getBytes(StandardCharsets.UTF_8));
                waiting.add(socket);
            }
            Thread.sleep(1000);
            assertThat(SecurityFilterThreadsRecorder.THREADS).as("answered while every slot was held").isEmpty();
            for (Socket socket : held) {
                socket.close();
            }
            for (Socket socket : waiting) {
                drain(socket);
            }
            awaitRecorded(extra);

            assertNoEventLoop(extra);
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
            for (Socket socket : waiting) {
                socket.close();
            }
        }
    }

    private static void awaitRecorded(int requests) throws InterruptedException {
        for (int i = 0; i < 100 && SecurityFilterThreadsRecorder.THREADS.size() < requests; i++) {
            Thread.sleep(100);
        }
    }
}
