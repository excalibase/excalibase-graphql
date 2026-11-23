package io.github.excalibase.sqlite;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** What takes the write lock is read from the statement itself: a word that is quoted, or a function, is no write. */
class SqliteWritesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO t VALUES (1)", "  update t set v = 1", "DELETE FROM t", "REPLACE INTO t VALUES (1)",
            "CREATE TABLE x (id)", "/* c */ INSERT INTO t VALUES (1)", "-- c\nUPDATE t SET v = 1",
            "WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x",
            "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 3) DELETE FROM t WHERE id IN (SELECT n FROM r)",
            "WITH a AS (SELECT 1), b AS (SELECT 2) UPDATE t SET v = (SELECT * FROM a)",
            "PRAGMA user_version = 3", "BEGIN IMMEDIATE"})
    void writes(String sql) {
        assertThat(SqliteWrites.writes(sql)).as(sql).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1", "select * from t where v = 'insert'", "SELECT \"update\" FROM t", "SELECT replace(v, 'a', 'b') FROM t",
            "WITH x AS (SELECT 1) SELECT * FROM x", "WITH \"delete\" AS (SELECT 1) SELECT * FROM \"delete\"",
            "WITH x AS (SELECT 'update') SELECT replace('a', 'a', 'b') FROM x", "WITH x AS (SELECT [insert] FROM t) SELECT * FROM x",
            "PRAGMA table_info(t)", "BEGIN", "COMMIT", "EXPLAIN INSERT INTO t VALUES (1)", "-- INSERT\nSELECT 1",
            "SELECT 'it''s an insert'"})
    void doesNotWrite(String sql) {
        assertThat(SqliteWrites.writes(sql)).as(sql).isFalse();
    }
}
