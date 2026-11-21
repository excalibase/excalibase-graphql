package io.github.excalibase.sqlite;

import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteSchemaLoaderTest {

    @TempDir
    Path directory;

    private SchemaInfo reflect(String... statements) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("t.db")));
        for (String statement : statements) {
            jdbc.execute(statement);
        }
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        new SqliteSchemaLoader().loadAll(jdbc, List.of("main"), perSchema);
        return perSchema.get("main");
    }

    @Test
    void tablesColumnsTypesAndKeys_areReflected() {
        SchemaInfo info = reflect(
                "CREATE TABLE parents (id INTEGER PRIMARY KEY, name VARCHAR(20) NOT NULL, ok BOOLEAN)",
                "CREATE TABLE kids (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parents(id), big BIGINT)",
                "CREATE VIEW parent_names AS SELECT name FROM parents");

        assertThat(info.getTableNames()).containsExactlyInAnyOrder("parents", "kids", "parent_names");
        assertThat(info.getColumnType("parents", "name")).isEqualTo("text");
        assertThat(info.getColumnType("parents", "ok")).isEqualTo("boolean");
        assertThat(info.getColumnType("kids", "big")).isEqualTo("bigint");
        assertThat(info.getPrimaryKeys("parents")).containsExactly("id");
        assertThat(info.isAutoIncrementColumn("parents", "id")).isTrue();
        assertThat(info.isView("parent_names")).isTrue();
        assertThat(info.getAllForwardFks().values()).singleElement()
                .satisfies(fk -> assertThat(fk.refTable()).isEqualTo("parents"));
    }

    @Test
    void compositeKeys_andForeignKeysWithoutTargetColumns_areReflected() {
        SchemaInfo info = reflect(
                "CREATE TABLE a (x INTEGER, y TEXT, PRIMARY KEY (y, x))",
                "CREATE TABLE b (x INTEGER, y TEXT, FOREIGN KEY (y, x) REFERENCES a)");

        assertThat(info.getPrimaryKeys("a")).containsExactly("y", "x");
        SchemaInfo.FkInfo fk = info.getAllForwardFks().values().iterator().next();
        assertThat(fk.fkColumns()).containsExactly("y", "x");
        assertThat(fk.refColumns()).containsExactly("y", "x");
    }

    @Test
    void tableWithoutPrimaryKey_isKeyedByItsFirstNotNullUniqueIndex() {
        SchemaInfo info = reflect(
                "CREATE TABLE loose (code TEXT NOT NULL, label TEXT)",
                "CREATE UNIQUE INDEX loose_code ON loose (code)",
                "CREATE TABLE nullable (code TEXT)",
                "CREATE UNIQUE INDEX nullable_code ON nullable (code)");

        assertThat(info.getPrimaryKeys("loose")).containsExactly("code");
        assertThat(info.hasPrimaryKey("nullable")).isFalse();
    }

    @Test
    void uniqueIndexes_areNamedForOnConflict_theKeyIsNot() {
        SchemaInfo info = reflect(
                "CREATE TABLE t (id INTEGER PRIMARY KEY, org INTEGER, slot TEXT, mail TEXT UNIQUE)",
                "CREATE UNIQUE INDEX t_org_slot ON t (org, slot)");

        assertThat(info.getUniqueKey("t", "t_org_slot")).containsExactly("org", "slot");
        assertThat(info.getUniqueKey("t", "sqlite_autoindex_t_1")).containsExactly("mail");
        assertThat(info.getUniqueKey("t", "PRIMARY")).isNull();
    }

    @Test
    void sqliteInternalTables_areNotReflected() {
        SchemaInfo info = reflect("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)", "INSERT INTO t DEFAULT VALUES");

        assertThat(info.getTableNames()).containsExactly("t");
    }
}
