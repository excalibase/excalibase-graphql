package io.github.excalibase.nosql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.nosql.schema.CollectionSchemaManager;
import io.github.excalibase.nosql.schema.JsonSchemaValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verifies {@link CollectionSchemaManager#createTable(String)} adds new
 * NoSQL collection tables to {@code cdc_watcher_pub}.
 *
 * Critically, the manager connects as <strong>excalibase_app</strong>
 * — not the postgres superuser — so this test catches the production
 * constraint that excalibase_app must own the publication for ALTER to
 * succeed. Connecting as superuser would mask permission bugs and let
 * the soft-fail path mask real failures.
 */
@Testcontainers
class RealtimeAutoEnableIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate adminJdbc;     // connects as test container's superuser; used only by setUp
    static JdbcTemplate appJdbc;       // connects as excalibase_app — used by schemaManager
    static CollectionSchemaManager schemaManager;
    static String appDsn;
    static final String APP_USER = "excalibase_app";
    static final String APP_PASS = "test_app_password";

    @BeforeAll
    static void setUp() {
        var adminDs = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        adminJdbc = new JdbcTemplate(adminDs);

        // Mirror production role topology: create excalibase_app + grant it
        // CREATE on public so it can own collection tables, plus pg_monitor
        // for stats. No REPLICATION attribute (the security boundary —
        // matches Phase 1 role model).
        adminJdbc.execute("CREATE ROLE " + APP_USER + " WITH LOGIN PASSWORD '" + APP_PASS + "'");
        adminJdbc.execute("GRANT CREATE, USAGE ON SCHEMA public TO " + APP_USER);
        adminJdbc.execute("GRANT pg_monitor TO " + APP_USER);
        adminJdbc.execute("GRANT CREATE ON DATABASE " + postgres.getDatabaseName() + " TO " + APP_USER);

        appDsn = postgres.getJdbcUrl();
        var appDs = new DriverManagerDataSource(appDsn, APP_USER, APP_PASS);
        appJdbc = new JdbcTemplate(appDs);

        schemaManager = new CollectionSchemaManager(appJdbc,
                new JsonSchemaValidator(new ObjectMapper()), null, "cdc_watcher_pub");
    }

    @BeforeEach
    void resetState() {
        // Wipe per-test state. nosql schema is owned by excalibase_app once
        // it creates anything; admin can DROP CASCADE since admin is
        // superuser. Same for the publication.
        adminJdbc.execute("DROP SCHEMA IF EXISTS nosql CASCADE");
        adminJdbc.execute("DROP PUBLICATION IF EXISTS cdc_watcher_pub");
    }

    @Test
    @DisplayName("excalibase_app adds collection to publication it owns")
    void newCollectionAutoAddedToPublication() {
        // Provisioning's setup: superuser creates an empty publication and
        // hands ownership to excalibase_app so the runtime role can ALTER it.
        adminJdbc.execute("CREATE PUBLICATION cdc_watcher_pub");
        adminJdbc.execute("ALTER PUBLICATION cdc_watcher_pub OWNER TO " + APP_USER);

        schemaManager.syncSchema(Map.of("collections", Map.of(
                "posts", Map.of("indexes", List.of())
        )));

        var members = adminJdbc.queryForList(
                "SELECT schemaname, tablename FROM pg_publication_tables WHERE pubname = 'cdc_watcher_pub'");

        assertThat(members)
                .as("excalibase_app should be able to add nosql.posts to the publication it owns")
                .anyMatch(row -> "nosql".equals(row.get("schemaname"))
                        && "posts".equals(row.get("tablename")));
    }

    @Test
    @DisplayName("excalibase_app cannot ALTER publication owned by someone else (soft-fail surfaces silent miss)")
    void publicationOwnedByOthersIsSilentlySkipped() {
        // Production has had projects where the publication was created but
        // never re-owned to excalibase_app. The runtime role can't alter it
        // and soft-fail keeps the user's INSERT path working. This test
        // pins that exact situation: publication exists but is owned by
        // postgres → excalibase_app gets permission denied → table is
        // still created.
        adminJdbc.execute("CREATE PUBLICATION cdc_watcher_pub");
        // intentionally NOT ALTER'd to excalibase_app

        schemaManager.syncSchema(Map.of("collections", Map.of(
                "events", Map.of("indexes", List.of())
        )));

        var tableExists = appJdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'nosql' AND tablename = 'events'",
                Integer.class);
        assertThat(tableExists).as("table is created even when ALTER PUBLICATION is denied").isEqualTo(1);

        var members = adminJdbc.queryForList(
                "SELECT tablename FROM pg_publication_tables WHERE pubname = 'cdc_watcher_pub'");
        assertThat(members)
                .as("ALTER PUBLICATION must NOT have succeeded — excalibase_app does not own it")
                .filteredOn(row -> "events".equals(row.get("tablename")))
                .isEmpty();
    }

    @Test
    @DisplayName("missing publication does not block table creation")
    void missingPublicationDoesNotBlockTableCreation() {
        // No publication exists. createTable still succeeds; ALTER attempt
        // is logged as WARN and swallowed.
        schemaManager.syncSchema(Map.of("collections", Map.of(
                "logs", Map.of("indexes", List.of())
        )));

        var tableExists = appJdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'nosql' AND tablename = 'logs'",
                Integer.class);
        assertThat(tableExists).isEqualTo(1);
    }

    @Test
    @DisplayName("re-sync over an existing collection is idempotent — already-member ALTER does not error")
    void resyncDoesNotErrorOnAlreadyMemberTable() {
        adminJdbc.execute("CREATE PUBLICATION cdc_watcher_pub");
        adminJdbc.execute("ALTER PUBLICATION cdc_watcher_pub OWNER TO " + APP_USER);

        schemaManager.syncSchema(Map.of("collections", Map.of(
                "users", Map.of("indexes", List.of())
        )));
        // Re-sync is a no-op for an existing table; the ALTER PUBLICATION
        // path triggers 42710 (already member) which the manager swallows.
        try {
            schemaManager.syncSchema(Map.of("collections", Map.of(
                    "users", Map.of("indexes", List.of())
            )));
        } catch (Exception e) {
            fail("re-sync threw on already-member table: " + e.getMessage());
        }

        var members = adminJdbc.queryForList(
                "SELECT tablename FROM pg_publication_tables WHERE pubname = 'cdc_watcher_pub'");
        assertThat(members)
                .filteredOn(row -> "users".equals(row.get("tablename")))
                .as("table appears exactly once in the publication")
                .hasSize(1);
    }
}
