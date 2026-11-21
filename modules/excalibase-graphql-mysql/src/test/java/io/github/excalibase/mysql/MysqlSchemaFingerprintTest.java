package io.github.excalibase.mysql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** The fingerprint a standalone engine polls moves on DDL the loader reflects, and not on rows or statistics. */
class MysqlSchemaFingerprintTest {

    private static MySQLContainer<?> mysql;
    private static JdbcTemplate jdbc;
    private static final MysqlSchemaLoader LOADER = new MysqlSchemaLoader();

    @BeforeAll
    static void start() {
        mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("shop");
        mysql.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.execute("CREATE TABLE parents (id int PRIMARY KEY)");
        jdbc.execute("CREATE TABLE kids (id int PRIMARY KEY, parent_id int, label varchar(10), code varchar(10))");
    }

    @AfterAll
    static void stop() {
        mysql.stop();
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
    @DisplayName("a table created or dropped moves it")
    void tables() {
        assertMoves("CREATE TABLE extra (id int)");
        assertMoves("DROP TABLE extra");
    }

    @Test
    @DisplayName("a column added, renamed, retyped, made NOT NULL or dropped moves it, though the count may not")
    void columns() {
        assertMoves("ALTER TABLE kids ADD COLUMN note text");
        assertMoves("ALTER TABLE kids RENAME COLUMN note TO remark");
        assertMoves("ALTER TABLE kids MODIFY COLUMN remark varchar(20)");
        assertMoves("ALTER TABLE kids MODIFY COLUMN remark varchar(20) NOT NULL");
        assertMoves("ALTER TABLE kids DROP COLUMN remark");
    }

    @Test
    @DisplayName("a foreign key added or dropped moves it")
    void foreignKeys() {
        assertMoves("ALTER TABLE kids ADD CONSTRAINT kids_parent FOREIGN KEY (parent_id) REFERENCES parents(id)");
        assertMoves("ALTER TABLE kids DROP FOREIGN KEY kids_parent");
    }

    @Test
    @DisplayName("a unique key, an index and a renamed index move it")
    void keys() {
        assertMoves("ALTER TABLE kids ADD UNIQUE KEY kids_code (code)");
        assertMoves("ALTER TABLE kids RENAME INDEX kids_code TO kids_code_unique");
        assertMoves("ALTER TABLE kids ADD INDEX kids_label (label)");
        assertMoves("ALTER TABLE kids ADD FULLTEXT KEY kids_text (label)");
    }

    @Test
    @DisplayName("a routine created or dropped moves it")
    void routines() {
        assertMoves("CREATE PROCEDURE answer() SELECT 42");
        assertMoves("DROP PROCEDURE answer");
    }

    @Test
    @DisplayName("rows and ANALYZE do not move it")
    void notSchema() {
        assertStays("INSERT INTO parents VALUES (1), (2), (3)");
        assertStays("UPDATE parents SET id = id + 10");
        assertStays("ANALYZE TABLE parents");
        assertStays("DELETE FROM parents");
    }
}
