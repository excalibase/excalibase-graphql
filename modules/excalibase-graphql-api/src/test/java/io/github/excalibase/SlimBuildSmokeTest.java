package io.github.excalibase;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs a built engine, as an image would (EXC-799): the jar or native binary named by {@code -Dslim.command}, as its own
 * process against a database in a container. A build serves the databases listed in {@code -Dslim.serves} and must
 * refuse, at start and naming the image to use, the others. The all-in-one build serves both. Without
 * {@code slim.command} (every ordinary test run) the test is skipped; the {@code slim-*} Maven profiles run it
 * after packaging.
 */
class SlimBuildSmokeTest {

    private static final String PROJECT = "smoke";
    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String command;
    private static Set<String> serves;

    @BeforeAll
    static void readBuild() {
        command = System.getProperty("slim.command");
        assumeTrue(command != null && !command.isBlank(), "slim.command names the build to run; not set");
        serves = Set.of(System.getProperty("slim.serves", "postgres,mysql").split(","));
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "mysql"})
    void aBuildServesTheDatabasesItCarriesAndRefusesTheOthers(String engine) throws Exception {
        if (serves.contains(engine)) {
            servesItsDatabase(engine);
        } else {
            refusesTheDatabase(engine);
        }
    }

    @Test
    void aBuildThatCarriesNothingToServeWouldBeAMisconfiguredTest() {
        assertThat(serves).isNotEmpty().isSubsetOf("postgres", "mysql");
    }

    private void servesItsDatabase(String engine) throws Exception {
        try (JdbcDatabaseContainer<?> database = database(engine)) {
            database.start();
            try (EngineProcess process = EngineProcess.start(settings(engine, database.getJdbcUrl(),
                    database.getUsername(), database.getPassword()))) {
                process.awaitServing();
                String schema = process.query("{ __schema { queryType { fields { name } } } }");
                Matcher table = Pattern.compile("\"name\":\"(\\w*SmokeItem)\"").matcher(schema);
                assertThat(table.find()).as("the engine reflected the database it was started on: " + schema).isTrue();
                assertThat(process.query("{ " + table.group(1) + " { name } }")).contains("from-" + engine);
            }
        }
    }

    private void refusesTheDatabase(String engine) throws Exception {
        String url = engine.equals("mysql") ? "jdbc:mysql://127.0.0.1:1/app" : "jdbc:postgresql://127.0.0.1:1/app";
        try (EngineProcess process = EngineProcess.start(settings(engine, url, "app", "secret"))) {
            assertThat(process.awaitExit()).as("a build without the %s plugin must not start on a %s database", engine, engine)
                    .isNotZero();
            assertThat(process.log())
                    .contains("Refusing to start")
                    .contains("excalibase/excalibase-graphql:<tag>-" + engine);
        }
    }

    private static JdbcDatabaseContainer<?> database(String engine) {
        return engine.equals("mysql")
                ? new MySQLContainer<>("mysql:8.4").withInitScript("init-slim-smoke-mysql.sql")
                : new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("init-slim-smoke-postgres.sql");
    }

    private static Map<String, String> settings(String engine, String url, String user, String password) {
        Map<String, String> env = new HashMap<>(Map.of(
                "SPRING_DATASOURCE_URL", url,
                "SPRING_DATASOURCE_USERNAME", user,
                "SPRING_DATASOURCE_PASSWORD", password,
                "APP_DATABASE_TYPE", engine,
                "APP_PROJECT_ID", PROJECT,
                "APP_SECURITY_JWT_ENABLED", "false",
                "APP_SECURITY_INSECURE_DEV_MODE", "true"));
        env.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME",
                engine.equals("mysql") ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver");
        return env;
    }

    /** The engine under test, as a child process with its output in a file. */
    private static final class EngineProcess implements AutoCloseable {
        private final Process process;
        private final Path logFile;
        private final int port;

        private EngineProcess(Process process, Path logFile, int port) {
            this.process = process;
            this.logFile = logFile;
            this.port = port;
        }

        static EngineProcess start(Map<String, String> settings) throws IOException {
            int port = freePort();
            List<String> line = new ArrayList<>();
            if (command.endsWith(".jar")) {
                line.addAll(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m", "-jar"));
            }
            line.add(Path.of(command).toAbsolutePath().toString());
            Path logFile = Files.createTempFile("slim-smoke-", ".log");
            ProcessBuilder builder = new ProcessBuilder(line).redirectErrorStream(true).redirectOutput(logFile.toFile());
            builder.environment().putAll(settings);
            builder.environment().put("SERVER_PORT", String.valueOf(port));
            builder.environment().put("MANAGEMENT_SERVER_PORT", String.valueOf(freePort()));
            return new EngineProcess(builder.start(), logFile, port);
        }

        void awaitServing() throws Exception {
            long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new AssertionError("the engine exited with " + process.exitValue() + ":\n" + log());
                }
                try {
                    if (post("{ __typename }").statusCode() == 200) {
                        return;
                    }
                } catch (IOException notYet) {
                    // not listening yet
                }
                Thread.sleep(500);
            }
            throw new AssertionError("the engine did not serve within " + START_TIMEOUT + ":\n" + log());
        }

        int awaitExit() throws IOException, InterruptedException {
            if (!process.waitFor(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("the engine kept running; it should have refused to start:\n" + log());
            }
            return process.exitValue();
        }

        String query(String graphql) throws Exception {
            HttpResponse<String> response = post(graphql);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return response.body();
        }

        private HttpResponse<String> post(String graphql) throws IOException, InterruptedException {
            String json = "{\"query\":\"" + graphql.replace("\"", "\\\"") + "\"}";
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + PROJECT + "/graphql"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        }

        String log() throws IOException {
            return Files.readString(logFile);
        }

        @Override
        public void close() throws IOException {
            process.destroyForcibly();
            Files.deleteIfExists(logFile);
        }

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        }
    }
}
