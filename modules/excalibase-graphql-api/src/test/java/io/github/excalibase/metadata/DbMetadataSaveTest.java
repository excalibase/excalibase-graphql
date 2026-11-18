package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.support.DatabaseAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Saving to the {@code db} source against a real Postgres: a compare-and-set inside the bounded transaction. */
class DbMetadataSaveTest {

    private static final String TABLE = DbDocumentLoader.TABLE;
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate sql;

    private ReloadingMetadataSource source;

    @BeforeAll
    static void start() {
        postgres.start();
        sql = DatabaseAccess.template(postgres);
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @BeforeEach
    void freshTable() {
        sql.execute("DROP SCHEMA IF EXISTS excalibase_meta CASCADE");
    }

    @AfterEach
    void close() {
        if (source != null) {
            source.close();
        }
    }

    private ReloadingMetadataSource open() {
        return openAs(postgres.getUsername(), postgres.getPassword(), true);
    }

    private ReloadingMetadataSource openAs(String user, String password, boolean migrate) {
        DbSettings settings = new DbSettings(postgres.getJdbcUrl(), user, password, migrate, Duration.ofHours(1),
                Duration.ofSeconds(3));
        source = DbMetadataSource.create(settings, ReservedSchemas.fromConfig(""), PROJECT,
                new SimpleMeterRegistry(), false);
        source.start();
        return source;
    }

    private void seed(String document) {
        sql.update("INSERT INTO " + TABLE + " (version, document) SELECT coalesce(max(version), 0) + 1, ? FROM "
                + TABLE, document);
    }

    private ReloadingMetadataSource openWithDocument() {
        open();
        seed(GOOD_YAML);
        source.refresh();
        source.refresh();
        assertThat(source.current()).isPresent();
        return source;
    }

    @Test
    void aSave_addsTheNextRow_andIsServedAfterTheReload() {
        ReloadingMetadataSource served = openWithDocument();

        SaveOutcome outcome = served.save(GOOD_YAML_TWO_RULES, served.current().orElseThrow().sourceVersion());

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT document FROM " + TABLE + " ORDER BY version DESC LIMIT 1",
                String.class)).isEqualTo(GOOD_YAML_TWO_RULES);
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(2);
    }

    @Test
    void aStaleVersion_isAConflict_andNothingIsWritten() {
        ReloadingMetadataSource served = openWithDocument();

        SaveOutcome outcome = served.save(GOOD_YAML_TWO_RULES, "stale");

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Conflict.class, conflict ->
                assertThat(conflict.currentVersion()).isEqualTo(served.current().orElseThrow().sourceVersion()));
        assertThat(sql.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class)).isEqualTo(1);
    }

    @Test
    void aRowSavedBehindTheEngine_isAConflict() {
        ReloadingMetadataSource served = openWithDocument();
        String expected = served.current().orElseThrow().sourceVersion();
        seed(GOOD_YAML + "# someone else\n");

        assertThat(served.save(GOOD_YAML_TWO_RULES, expected)).isInstanceOf(SaveOutcome.Conflict.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class)).isEqualTo(2);
    }

    @Test
    void twoWritersWithTheSameVersion_exactlyOneWins() throws Exception {
        ReloadingMetadataSource served = openWithDocument();
        String expected = served.current().orElseThrow().sourceVersion();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<SaveOutcome>> running = List.of(
                    pool.submit(contend(go, served, GOOD_YAML_TWO_RULES, expected)),
                    pool.submit(contend(go, served, GOOD_YAML + "# b\n", expected)),
                    pool.submit(contend(go, served, GOOD_YAML + "# c\n", expected)),
                    pool.submit(contend(go, served, GOOD_YAML + "# d\n", expected)));
            go.countDown();
            int saved = 0;
            for (Future<SaveOutcome> run : running) {
                SaveOutcome outcome = run.get();
                saved += outcome instanceof SaveOutcome.Saved ? 1 : 0;
                assertThat(outcome).isNotInstanceOf(SaveOutcome.Failed.class);
            }
            assertThat(saved).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<SaveOutcome> contend(CountDownLatch go, ReloadingMetadataSource served, String text,
                                                  String expected) {
        return () -> {
            go.await();
            return served.save(text, expected);
        };
    }

    @Test
    void aFailingWrite_isReported_andTheLastGoodDocumentStaysServed() {
        ReloadingMetadataSource served = openWithDocument();
        sql.execute("CREATE FUNCTION excalibase_meta.refuse() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'no writes'; END $$");
        sql.execute("CREATE TRIGGER refuse BEFORE INSERT ON " + TABLE
                + " FOR EACH ROW EXECUTE FUNCTION excalibase_meta.refuse()");

        SaveOutcome outcome = served.save(GOOD_YAML_TWO_RULES, served.current().orElseThrow().sourceVersion());

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Failed.class, failed ->
                assertThat(failed.category()).doesNotContain("no writes"));
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(served.writable()).isTrue();
    }

    @Test
    void anInvalidDocument_isRefused_andNeverWritten() {
        ReloadingMetadataSource served = openWithDocument();

        assertThat(served.save(BAD_UNKNOWN_KEY, served.current().orElseThrow().sourceVersion()))
                .isInstanceOf(SaveOutcome.Invalid.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class)).isEqualTo(1);
    }

    @Test
    void aFirstDocument_canBeSaved_whenTheTableIsEmpty() {
        ReloadingMetadataSource served = open();
        assertThat(served.current()).isEmpty();

        assertThat(served.save(GOOD_YAML, MetadataWriter.NO_DOCUMENT)).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(served.save(GOOD_YAML_TWO_RULES, MetadataWriter.NO_DOCUMENT)).isInstanceOf(SaveOutcome.Conflict.class);
    }

    @Test
    void aRowCommittedWhileTheWriterWaits_isAConflict_notAFailedWrite() throws Exception {
        ReloadingMetadataSource served = openWithDocument();
        String expected = served.current().orElseThrow().sourceVersion();
        String raced = GOOD_YAML + "# raced\n";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            other.setAutoCommit(false);
            insertUncommitted(other, raced);
            Future<SaveOutcome> save = pool.submit(() -> served.save(GOOD_YAML_TWO_RULES, expected));
            await().atMost(Duration.ofSeconds(10)).until(() -> sql.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'excalibase-metadata' "
                            + "AND wait_event_type = 'Lock'", Integer.class) > 0);

            other.commit();

            assertThat(save.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(SaveOutcome.Conflict.class,
                    conflict -> assertThat(conflict.currentVersion())
                            .isEqualTo(FileMetadataSource.sha256(raced.getBytes(StandardCharsets.UTF_8))));
        } finally {
            pool.shutdownNow();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class)).isEqualTo(2);
    }

    private static void insertUncommitted(Connection connection, String document) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + TABLE + " (version, document) VALUES (2, '"
                    + document.replace("'", "''") + "')");
        }
    }

    @Test
    void aUserThatCanOnlySelectAndInsert_canSave() {
        openWithDocument();
        source.close();
        sql.execute("DROP ROLE IF EXISTS meta_writer");
        sql.execute("CREATE ROLE meta_writer LOGIN PASSWORD 'meta-writer-password'");
        sql.execute("GRANT USAGE ON SCHEMA excalibase_meta TO meta_writer");
        sql.execute("GRANT SELECT, INSERT ON " + TABLE + " TO meta_writer");
        ReloadingMetadataSource restricted = openAs("meta_writer", "meta-writer-password", false);
        restricted.refresh();
        restricted.refresh();

        SaveOutcome outcome = restricted.save(GOOD_YAML_TWO_RULES, restricted.current().orElseThrow().sourceVersion());

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class)).isEqualTo(2);
    }

    @Test
    void aUserWithoutTheRightToWrite_getsAMessageAboutThePrivilege_notAboutReading() {
        assertThat(DbDocumentLoader.describe(new SQLException("denied", "42501")))
                .doesNotContain("may not read").contains("privilege");
    }
}
