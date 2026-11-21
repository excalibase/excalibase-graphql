package io.github.excalibase.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A standalone engine polls the fingerprint to see tables created after it started. */
class SqliteSchemaFingerprintTest {

    @TempDir
    Path directory;

    private final SqliteSchemaLoader loader = new SqliteSchemaLoader();

    private JdbcTemplate file() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("t.db")));
    }

    @Test
    void aNewTable_aNewColumnAndAnIndex_changeIt() {
        JdbcTemplate jdbc = file();
        jdbc.execute("CREATE TABLE a (id INTEGER PRIMARY KEY)");
        String first = loader.fingerprint(jdbc);

        jdbc.execute("CREATE TABLE b (id INTEGER PRIMARY KEY)");
        String withTable = loader.fingerprint(jdbc);
        jdbc.execute("ALTER TABLE a ADD COLUMN name TEXT");
        String withColumn = loader.fingerprint(jdbc);
        jdbc.execute("CREATE UNIQUE INDEX a_name ON a (name)");
        String withIndex = loader.fingerprint(jdbc);

        assertThat(first).isNotNull();
        assertThat(List.of(first, withTable, withColumn, withIndex)).doesNotHaveDuplicates();
    }

    @Test
    void rowWrites_andAReadFromAnotherConnection_leaveItAlone() {
        JdbcTemplate jdbc = file();
        jdbc.execute("CREATE TABLE a (id INTEGER PRIMARY KEY, name TEXT)");
        String before = loader.fingerprint(jdbc);

        jdbc.update("INSERT INTO a (name) VALUES ('x'), ('y')");
        jdbc.update("UPDATE a SET name = 'z'");
        jdbc.update("DELETE FROM a");

        assertThat(loader.fingerprint(file())).isEqualTo(before);
    }

    @Test
    void aTableCreatedByAnotherConnection_isSeen() {
        JdbcTemplate engine = file();
        engine.execute("CREATE TABLE a (id INTEGER PRIMARY KEY)");
        String before = loader.fingerprint(engine);

        file().execute("CREATE TABLE late (id INTEGER PRIMARY KEY)");

        assertThat(loader.fingerprint(engine)).isNotEqualTo(before);
    }
}
