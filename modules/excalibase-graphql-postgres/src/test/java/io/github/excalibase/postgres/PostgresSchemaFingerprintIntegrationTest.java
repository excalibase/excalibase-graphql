package io.github.excalibase.postgres;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint a standalone engine polls: it must move on every DDL the loader would reflect differently,
 * and stay put on what is not schema (rows, statistics, vacuum).
 */
class PostgresSchemaFingerprintIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static final PostgresSchemaLoader LOADER = new PostgresSchemaLoader();

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setAutoCommit(true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE ROLE fp_reader");
        jdbc.execute("CREATE TYPE mood AS ENUM ('sad', 'ok')");
        jdbc.execute("CREATE TABLE parents (id int PRIMARY KEY)");
        jdbc.execute("CREATE TABLE kids (id int PRIMARY KEY, parent_id int, label varchar(10), mood mood)");
        jdbc.execute("CREATE FUNCTION answer(x int) RETURNS int LANGUAGE sql AS 'select x'");
        jdbc.execute("CREATE SCHEMA renamable");
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        postgres.stop();
    }

    private static String fingerprint() {
        return LOADER.fingerprint(jdbc);
    }

    private static void assertMoves(String sql) {
        String before = fingerprint();
        jdbc.execute(sql);
        assertThat(fingerprint()).as(sql).isNotNull().isNotEqualTo(before);
    }

    private static void assertStays(String sql) {
        String before = fingerprint();
        jdbc.execute(sql);
        assertThat(fingerprint()).as(sql).isEqualTo(before);
    }

    @Test
    @DisplayName("a new table, a dropped table and a new view move it")
    void tablesAndViews() {
        assertMoves("CREATE TABLE extra (id int)");
        assertMoves("CREATE VIEW extra_view AS SELECT id FROM extra");
        assertMoves("DROP VIEW extra_view");
        assertMoves("DROP TABLE extra");
    }

    @Test
    @DisplayName("a column added, renamed, retyped, made NOT NULL or dropped moves it")
    void columns() {
        assertMoves("ALTER TABLE kids ADD COLUMN note text");
        assertMoves("ALTER TABLE kids RENAME COLUMN note TO remark");
        assertMoves("ALTER TABLE kids ALTER COLUMN remark TYPE varchar(20)");
        assertMoves("ALTER TABLE kids ALTER COLUMN remark SET NOT NULL");
        assertMoves("ALTER TABLE kids DROP COLUMN remark");
    }

    @Test
    @DisplayName("a foreign key added or dropped moves it")
    void foreignKeys() {
        assertMoves("ALTER TABLE kids ADD CONSTRAINT kids_parent FOREIGN KEY (parent_id) REFERENCES parents(id)");
        assertMoves("ALTER TABLE kids DROP CONSTRAINT kids_parent");
    }

    @Test
    @DisplayName("an enum value added moves it")
    void enums() {
        assertMoves("ALTER TYPE mood ADD VALUE 'great'");
    }

    @Test
    @DisplayName("a function replaced with another return type, volatility or default moves it")
    void functions() {
        assertMoves("CREATE OR REPLACE FUNCTION answer(x int) RETURNS int LANGUAGE sql IMMUTABLE AS 'select x'");
        assertMoves("CREATE OR REPLACE FUNCTION answer(x int, y int DEFAULT 1) RETURNS int LANGUAGE sql AS 'select x'");
        assertMoves("DROP FUNCTION answer(int, int)");
        assertMoves("DROP FUNCTION answer(int)");
        assertMoves("CREATE FUNCTION answer(x int) RETURNS bigint LANGUAGE sql AS 'select x::bigint'");
    }

    @Test
    @DisplayName("GRANT and REVOKE on a table or a column move it")
    void privileges() {
        assertMoves("GRANT SELECT ON kids TO fp_reader");
        assertMoves("GRANT UPDATE (label) ON kids TO fp_reader");
        assertMoves("REVOKE SELECT ON kids FROM fp_reader");
    }

    @Test
    @DisplayName("a schema renamed moves it")
    void schemaRename() {
        assertMoves("ALTER SCHEMA renamable RENAME TO renamed");
    }

    @Test
    @DisplayName("rows, ANALYZE, VACUUM and autovacuum-style statistics do not move it")
    void notSchema() {
        assertStays("INSERT INTO parents VALUES (1), (2), (3)");
        assertStays("UPDATE parents SET id = id + 10");
        assertStays("ANALYZE");
        assertStays("VACUUM parents");
        assertStays("DELETE FROM parents");
    }
}
