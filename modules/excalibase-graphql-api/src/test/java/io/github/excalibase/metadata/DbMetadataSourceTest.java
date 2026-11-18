package io.github.excalibase.metadata;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.support.DatabaseAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.stream.Collectors;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code db} source against a real Postgres: boot, live edits, refusals, outages and what never reaches a log. */
class DbMetadataSourceTest {

    private static final String TABLE = "excalibase_meta.metadata_documents";
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate sql;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private ReloadingMetadataSource source;

    @BeforeAll
    static void startDatabase() {
        postgres.start();
        sql = DatabaseAccess.template(postgres);
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    @BeforeEach
    void cleanSlate() {
        sql.execute("DROP SCHEMA IF EXISTS excalibase_meta CASCADE");
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void close() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        if (source != null) {
            source.close();
        }
    }

    private static void createTable() {
        sql.execute("CREATE SCHEMA excalibase_meta");
        sql.execute("CREATE TABLE " + TABLE + " (version bigint PRIMARY KEY, document text NOT NULL, "
                + "created_at timestamptz NOT NULL DEFAULT now())");
    }

    private static void save(long version, String document) {
        sql.update("INSERT INTO " + TABLE + " (version, document) VALUES (?, ?)", version, document);
    }

    private static DbSettings settings(boolean migrate) {
        return new DbSettings(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), migrate,
                Duration.ofHours(1), Duration.ofSeconds(2));
    }

    private ReloadingMetadataSource create(DbSettings settings, boolean envJwt) {
        source = DbMetadataSource.create(settings, ReservedSchemas.fromConfig(""), PROJECT, meters, envJwt);
        return source;
    }

    /** A changed document is believed only on the second identical read. */
    private static void poll(ReloadingMetadataSource source) {
        source.refresh();
        source.refresh();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bootsFromAValidRow_andReportsTheBodyHashAsTheVersion() throws Exception {
        createTable();
        save(1, GOOD_YAML);

        create(settings(false), false).start();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.name()).isEqualTo("db");
        assertThat(source.status().sourceVersion()).isEqualTo(sha256(GOOD_YAML));
    }

    @Test
    void aNewerRow_isPickedUpOnTheNextPoll_andTheHighestVersionWins() {
        createTable();
        save(1, GOOD_YAML);
        create(settings(false), false).start();

        save(2, GOOD_YAML_TWO_RULES);
        save(0, BAD_RULE);
        poll(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
    }

    @Test
    void aChangedRow_isNotBelievedOnFirstSight() {
        createTable();
        save(1, GOOD_YAML);
        create(settings(false), false).start();

        save(2, GOOD_YAML_TWO_RULES);
        source.refresh();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void anInvalidEdit_isRefused_andTheLastGoodVersionKeepsServing() {
        createTable();
        save(1, GOOD_YAML);
        create(settings(false), false).start();

        save(2, BAD_RULE);
        poll(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
    }

    @Test
    void aMissingTable_atStart_refusesEverything_andStaysNotReady() {
        create(settings(false), false).start();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.current()).isEmpty();
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        assertThat(tableExists()).as("the engine did not create it unasked").isFalse();
    }

    @Test
    void aMissingRow_atStart_refusesEverything_untilARowArrives() {
        createTable();
        create(settings(false), false).start();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(new MetadataHealth(source).ready()).isFalse();

        save(1, GOOD_YAML);
        poll(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(new MetadataHealth(source).ready()).isTrue();
    }

    @Test
    void withMigrateOn_theEngineCreatesTheTable_andWaitsForARow() {
        create(settings(true), false).start();

        assertThat(tableExists()).isTrue();
        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);

        save(1, GOOD_YAML);
        poll(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void aJwtSection_isRefused_whenTheEngineHasEnvJwt() {
        createTable();
        save(1, WITH_JWT);

        create(settings(false), true).start();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
    }

    @Test
    void theDatabaseGoingAway_midRun_keepsTheLastGoodVersion() {
        try (PostgreSQLContainer<?> own = new PostgreSQLContainer<>("postgres:16-alpine")) {
            own.start();
            JdbcTemplate ownSql = DatabaseAccess.template(own);
            ownSql.execute("CREATE SCHEMA excalibase_meta");
            ownSql.execute("CREATE TABLE " + TABLE + " (version bigint PRIMARY KEY, document text NOT NULL, "
                    + "created_at timestamptz NOT NULL DEFAULT now())");
            ownSql.update("INSERT INTO " + TABLE + " (version, document) VALUES (1, ?)", GOOD_YAML);
            create(new DbSettings(own.getJdbcUrl(), own.getUsername(), own.getPassword(), false,
                    Duration.ofHours(1), Duration.ofSeconds(1)), false).start();
            assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);

            own.stop();
            poll(source);

            assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
            assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        }
    }

    @Test
    void theMetadataCredentials_neverAppearInALogOrAnError() {
        String urlSecret = "url-secret-4711";
        String passwordSecret = "pw-secret-0815";
        sql.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'meta_reader') THEN "
                + "CREATE ROLE meta_reader LOGIN PASSWORD 'right-password'; END IF; END $$");
        DbSettings unreachable = new DbSettings("jdbc:postgresql://127.0.0.1:1/db?password=" + urlSecret,
                "meta_reader", passwordSecret, true, Duration.ofHours(1), Duration.ofSeconds(1));
        DbSettings wrongPassword = new DbSettings(postgres.getJdbcUrl() + "&sslmode=disable", "meta_reader",
                passwordSecret, true, Duration.ofHours(1), Duration.ofSeconds(1));

        for (DbSettings settings : new DbSettings[] {unreachable, wrongPassword}) {
            create(settings, false).start();
            assertThatThrownBy(() -> source.permissionsFor(PROJECT))
                    .isInstanceOf(PermissionsUnavailableException.class)
                    .satisfies(error -> assertThat(error.toString()).doesNotContain(urlSecret, passwordSecret));
            assertThat(settings.toString()).doesNotContain(urlSecret, passwordSecret, "meta_reader");
            source.close();
        }

        String everything = logs.list.stream().map(event -> event.getFormattedMessage()
                + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(Collectors.joining("\n"));
        assertThat(everything).contains("metadata_refused").doesNotContain(urlSecret, passwordSecret);
    }

    private static boolean tableExists() {
        return Boolean.TRUE.equals(sql.queryForObject("SELECT to_regclass('" + TABLE + "') IS NOT NULL", Boolean.class));
    }
}
