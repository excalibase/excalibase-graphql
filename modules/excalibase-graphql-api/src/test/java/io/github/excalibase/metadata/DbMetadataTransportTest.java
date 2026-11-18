package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.support.DatabaseAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** What the {@code db} source does to a connection it shares with others, and when the server stops answering. */
class DbMetadataTransportTest {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate sql;

    @BeforeAll
    static void start() {
        postgres.start();
        sql = DatabaseAccess.template(postgres);
        sql.execute("CREATE SCHEMA excalibase_meta");
        sql.execute("CREATE TABLE excalibase_meta.metadata_documents (version bigint PRIMARY KEY, "
                + "document text NOT NULL, created_at timestamptz NOT NULL DEFAULT now())");
        sql.update("INSERT INTO excalibase_meta.metadata_documents (version, document) VALUES (1, ?)", GOOD_YAML);
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    private static DbSettings settings(String url, Duration queryTimeout) {
        return new DbSettings(url, postgres.getUsername(), postgres.getPassword(), false, Duration.ofHours(1),
                Duration.ofSeconds(1), queryTimeout);
    }

    /** A connection whose close does nothing, standing in for the backend a transaction-mode pooler hands on. */
    private static Connection keepOpen(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> "close".equals(method.getName()) ? null : method.invoke(real, args));
    }

    private static String show(Connection connection, String setting) throws Exception {
        try (ResultSet rows = connection.createStatement().executeQuery("SHOW " + setting)) {
            rows.next();
            return rows.getString(1);
        }
    }

    @Test
    void theTimeouts_doNotOutliveTheRead_onAConnectionTheNextClientReuses() throws Exception {
        try (Connection shared = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            String before = show(shared, "statement_timeout");
            DbDocumentLoader loader = new DbDocumentLoader(settings(postgres.getJdbcUrl(), Duration.ofSeconds(7)),
                    () -> keepOpen(shared));

            assertThat(loader.load().text()).isEqualTo(GOOD_YAML);

            assertThat(show(shared, "statement_timeout")).isEqualTo(before);
            assertThat(show(shared, "lock_timeout")).isEqualTo("0");
        }
    }

    private enum Mode { FORWARD, REFUSE_TO_ANSWER, STALL_AFTER_LOGIN }

    /** The server's "ready for query" message: the last thing it sends when a login completes. */
    private static final byte[] READY = {'Z', 0, 0, 0, 5};

    /** Forwards to Postgres; in the other modes it accepts connections and then stops answering. */
    private static final class Forwarder implements AutoCloseable {
        private final ServerSocket listener;
        private final List<Socket> open = new CopyOnWriteArrayList<>();
        private volatile Mode mode = Mode.FORWARD;

        Forwarder() throws IOException {
            listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(this::acceptLoop);
        }

        String url() {
            return "jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + "/" + postgres.getDatabaseName();
        }

        void mode(Mode mode) {
            this.mode = mode;
        }

        private void acceptLoop() {
            try {
                while (true) {
                    Socket client = listener.accept();
                    open.add(client);
                    if (mode != Mode.REFUSE_TO_ANSWER) {
                        Socket server = new Socket(postgres.getHost(), postgres.getFirstMappedPort());
                        open.add(server);
                        boolean stall = mode == Mode.STALL_AFTER_LOGIN;
                        Thread.ofVirtual().start(() -> pipe(client, server, false));
                        Thread.ofVirtual().start(() -> pipe(server, client, stall));
                    }
                }
            } catch (IOException _) {
                // closed
            }
        }

        /** With {@code stallAfterLogin}, nothing the server sends after its first ready-for-query is passed on. */
        private void pipe(Socket from, Socket to, boolean stallAfterLogin) {
            byte[] buffer = new byte[8192];
            boolean stalled = false;
            try {
                int read;
                while ((read = from.getInputStream().read(buffer)) >= 0) {
                    if (stalled) {
                        continue;
                    }
                    int end = read;
                    if (stallAfterLogin) {
                        int at = indexOf(buffer, read);
                        if (at >= 0) {
                            end = at + READY.length + 1;
                            stalled = true;
                        }
                    }
                    to.getOutputStream().write(buffer, 0, end);
                }
            } catch (IOException _) {
                // the other side went away
            }
        }

        private static int indexOf(byte[] buffer, int length) {
            for (int i = 0; i + READY.length <= length; i++) {
                if (Arrays.equals(buffer, i, i + READY.length, READY, 0, READY.length)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public void close() throws IOException {
            listener.close();
            for (Socket socket : open) {
                socket.close();
            }
        }
    }

    private void assertGivenUpOn(Mode mode) throws Exception {
        try (Forwarder forwarder = new Forwarder();
             ReloadingMetadataSource source = DbMetadataSource.create(settings(forwarder.url(), Duration.ofSeconds(1)),
                     ReservedSchemas.fromConfig(""), PROJECT, new SimpleMeterRegistry(), false)) {
            source.start();
            assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);

            forwarder.mode(mode);
            long started = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(20), source::refresh);
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(took).as("one poll: the query timeout plus the grace").isLessThan(Duration.ofSeconds(10));
            assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
            assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        }
    }

    @Test
    void aServerThatAcceptsButNeverAnswers_isGivenUpOnWithinTheBound_keepingTheLastGoodVersion() throws Exception {
        assertGivenUpOn(Mode.REFUSE_TO_ANSWER);
    }

    @Test
    void aServerThatStallsAfterLogin_isGivenUpOnWithinTheBound_keepingTheLastGoodVersion() throws Exception {
        assertGivenUpOn(Mode.STALL_AFTER_LOGIN);
    }

    @Test
    void noReadStrandsAnException_whenTheServerIsGone() {
        assertThatThrownBy(() -> new DbDocumentLoader(settings("jdbc:postgresql://127.0.0.1:1/db", Duration.ofSeconds(1)))
                .load()).isInstanceOf(IOException.class);
    }
}
