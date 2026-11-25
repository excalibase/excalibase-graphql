package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** When the store stops answering, goes away, or answers forever: every read is bounded and the last good version stays. */
class S3MetadataTransportTest {

    private static final String BUCKET = "meta";
    private static final String KEY = "permissions.yaml";
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static MinioFixture minio;

    @BeforeAll
    static void start() {
        minio = new MinioFixture().start();
        minio.createBucket(BUCKET);
        minio.put(BUCKET, KEY, GOOD_YAML);
    }

    @AfterAll
    static void stop() {
        minio.close();
    }

    private enum Mode { FORWARD, REFUSE_TO_ANSWER }

    /** Forwards to MinIO; in the other mode it accepts connections and then stops answering. */
    private static final class Forwarder implements AutoCloseable {
        private final ServerSocket listener;
        private final List<Socket> open = new CopyOnWriteArrayList<>();
        private final Object lock = new Object();
        private volatile Mode mode = Mode.FORWARD;
        private boolean closed;

        Forwarder() throws IOException {
            listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(this::acceptLoop);
        }

        String endpoint() {
            return "http://127.0.0.1:" + listener.getLocalPort();
        }

        /** Existing connections are dropped too: the client keeps its connection alive and would reuse a working one. */
        void mode(Mode mode) throws IOException {
            synchronized (lock) {
                this.mode = mode;
                closeOpenSockets();
            }
        }

        private void acceptLoop() {
            try {
                while (true) {
                    Socket client = listener.accept();
                    registerOrDrop(client);
                }
            } catch (IOException _) {
                // closed
            }
        }

        /**
         * The connection to MinIO is made outside the lock, with a timeout, so a slow connect never blocks a close.
         * Registering and the mode check then happen under the lock that closing takes: a connection accepted just
         * before a close or a mode change is either closed with the others or sees the new state, never left forwarding.
         */
        private void registerOrDrop(Socket client) throws IOException {
            Socket server = null;
            if (mode == Mode.FORWARD) {
                server = new Socket();
                server.connect(new InetSocketAddress(minio.host(), minio.port()), CONNECT_TIMEOUT_MILLIS);
            }
            synchronized (lock) {
                open.add(client);
                if (closed || mode != Mode.FORWARD || server == null) {
                    if (server != null) {
                        server.close();
                    }
                    if (closed) {
                        client.close();
                    }
                    return;
                }
                open.add(server);
                Socket upstream = server;
                Thread.ofVirtual().start(() -> pipe(client, upstream));
                Thread.ofVirtual().start(() -> pipe(upstream, client));
            }
        }

        private void closeOpenSockets() throws IOException {
            for (Socket socket : open) {
                socket.close();
            }
        }

        private static void pipe(Socket from, Socket to) {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException _) {
                // the other side went away
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (lock) {
                closed = true;
                listener.close();
                closeOpenSockets();
            }
        }
    }

    /** Answers with a header that promises a body, sends a little of it, and then says nothing more. */
    private static final class StallingBody implements AutoCloseable {
        private final ServerSocket listener;
        private final List<Socket> open = new CopyOnWriteArrayList<>();

        StallingBody() throws IOException {
            listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Socket client = listener.accept();
                        open.add(client);
                        Thread.ofVirtual().start(() -> answer(client));
                    }
                } catch (IOException _) {
                    // closed
                }
            });
        }

        private static void answer(Socket client) {
            try {
                client.getInputStream().read(new byte[4096]);
                client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\nformat: 1\n")
                        .getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
            } catch (IOException _) {
                // gone
            }
        }

        String endpoint() {
            return "http://127.0.0.1:" + listener.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            listener.close();
            for (Socket socket : open) {
                socket.close();
            }
        }
    }

    private static S3Settings settings(String endpoint) {
        return new S3Settings(endpoint, BUCKET, KEY, MinioFixture.REGION, null, true,
                S3Settings.Credentials.literal(MinioFixture.ACCESS_KEY, MinioFixture.SECRET_KEY),
                Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private ReloadingMetadataSource create(String endpoint) {
        return S3MetadataSource.create(settings(endpoint), ReservedSchemas.fromConfig(""), PROJECT,
                new SimpleMeterRegistry(), false);
    }

    @Test
    void theStoreGoingAway_midRun_keepsTheLastGoodVersion() throws Exception {
        Forwarder forwarder = new Forwarder();
        ReloadingMetadataSource source = create(forwarder.endpoint());
        try {
            source.start();
            assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);

            forwarder.close();
            source.refresh();
            source.refresh();

            assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
            assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        } finally {
            source.close();
        }
    }

    @Test
    void aStoreThatAcceptsButNeverAnswers_isGivenUpOn_withinTheBound_keepingTheLastGoodVersion() throws Exception {
        Forwarder forwarder = new Forwarder();
        ReloadingMetadataSource source = create(forwarder.endpoint());
        try {
            source.start();
            forwarder.mode(Mode.REFUSE_TO_ANSWER);

            long started = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(20), source::refresh);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
            assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
            assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        } finally {
            forwarder.close();
            source.close();
        }
    }

    @Test
    void aBodyThatStallsAfterTheHeaders_isGivenUpOn_withinTheBound() throws Exception {
        try (StallingBody stalling = new StallingBody()) {
            ReloadingMetadataSource source = create(stalling.endpoint());
            try {
                long started = System.nanoTime();
                assertTimeoutPreemptively(Duration.ofSeconds(20), source::start);

                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
                assertThat(source.current()).isEmpty();
                assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
            } finally {
                source.close();
            }
        }
    }
}
