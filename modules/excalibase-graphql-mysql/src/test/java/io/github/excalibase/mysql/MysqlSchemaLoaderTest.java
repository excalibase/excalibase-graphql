package io.github.excalibase.mysql;

import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaInfo.ColumnDeclaration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the loader records beyond the data type: declarations permissions compile from, and whole keys. */
class MysqlSchemaLoaderTest {

    private static MySQLContainer<?> mysql;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("shop");
        mysql.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.execute("CREATE TABLE shop.teams (org int, code varchar(10) COLLATE utf8mb4_bin, PRIMARY KEY (org, code))");
        jdbc.execute("CREATE TABLE shop.players (id int unsigned PRIMARY KEY, active tinyint(1), level tinyint,"
                + " mood enum('sad','it''s ok') COLLATE utf8mb4_0900_ai_ci, team_org int, team_code varchar(10) COLLATE utf8mb4_bin,"
                + " FOREIGN KEY (team_org, team_code) REFERENCES shop.teams(org, code))");
    }

    @AfterAll
    static void stop() {
        mysql.stop();
    }

    private static SchemaInfo bulkLoaded() {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        new MysqlSchemaLoader().loadAll(jdbc, List.of("shop"), perSchema);
        return perSchema.get("shop");
    }

    @Test
    void bulkLoad_recordsEachColumnsDeclaration() {
        SchemaInfo schema = bulkLoaded();

        assertThat(schema.getColumnDeclaration("players", "active")).isEqualTo(new ColumnDeclaration("tinyint(1)", null, null));
        assertThat(schema.getColumnDeclaration("players", "id").columnType()).isEqualTo("int unsigned");
        assertThat(schema.getColumnDeclaration("players", "mood"))
                .isEqualTo(new ColumnDeclaration("enum('sad','it''s ok')", "utf8mb4", "utf8mb4_0900_ai_ci"));
        assertThat(schema.getColumnDeclaration("teams", "code"))
                .isEqualTo(new ColumnDeclaration("varchar(10)", "utf8mb4", "utf8mb4_bin"));
        assertThat(schema.getColumnType("players", "mood")).isEqualTo("varchar");
    }

    @Test
    void bulkLoad_keepsACompositeForeignKeyWhole() {
        SchemaInfo schema = bulkLoaded();

        assertThat(schema.getAllForwardFks().values()).singleElement().satisfies(fk -> {
            assertThat(fk.fkColumns()).containsExactly("team_org", "team_code");
            assertThat(fk.refColumns()).containsExactly("org", "code");
            assertThat(fk.refTable()).isEqualTo("teams");
        });
    }

    @Test
    void perPartLoad_agreesWithTheBulkLoad() {
        SchemaInfo schema = new SchemaInfo();
        MysqlSchemaLoader loader = new MysqlSchemaLoader();
        loader.loadColumns(jdbc, "shop", schema);
        loader.loadForeignKeys(jdbc, "shop", schema);

        assertThat(schema.getColumnDeclaration("players", "mood").collation()).isEqualTo("utf8mb4_0900_ai_ci");
        assertThat(schema.getAllForwardFks().values()).singleElement()
                .satisfies(fk -> assertThat(fk.fkColumns()).containsExactly("team_org", "team_code"));
    }
}
