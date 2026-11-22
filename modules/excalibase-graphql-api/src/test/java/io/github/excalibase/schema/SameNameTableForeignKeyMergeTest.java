package io.github.excalibase.schema;

import io.github.excalibase.mysql.MysqlSchemaLoader;
import io.github.excalibase.spi.SchemaLoader;
import io.github.excalibase.postgres.PostgresSchemaLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two schemas that each own a table named users: a key into the other schema's table binds there, not to the
 * same-named table of its own schema, for every loader that reads several schemas at once. (EXC-776)
 */
class SameNameTableForeignKeyMergeTest {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("shop_a");

    @BeforeAll
    static void start() throws Exception {
        postgres.start();
        mysql.start();
        JdbcTemplate pg = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));
        for (String statement : List.of("CREATE SCHEMA shop_b",
                "CREATE TABLE public.users (id INT PRIMARY KEY)",
                "CREATE TABLE shop_b.users (id INT PRIMARY KEY)",
                "CREATE TABLE shop_b.orders (id INT PRIMARY KEY, user_id INT REFERENCES public.users (id))")) {
            pg.execute(statement);
        }
        for (String statement : List.of("CREATE DATABASE shop_b", "GRANT ALL ON *.* TO 'test'@'%'",
                "CREATE TABLE shop_a.users (id INT PRIMARY KEY)",
                "CREATE TABLE shop_b.users (id INT PRIMARY KEY)",
                "CREATE TABLE shop_b.orders (id INT PRIMARY KEY, user_id INT,"
                        + " FOREIGN KEY (user_id) REFERENCES shop_a.users (id))",
                "CREATE TABLE shop_a.codes (a INT, b INT, PRIMARY KEY (a, b))",
                "CREATE TABLE shop_b.codes (a INT, b INT, PRIMARY KEY (a, b))",
                "CREATE TABLE shop_b.coded (id INT PRIMARY KEY, a INT, b INT,"
                        + " FOREIGN KEY (a, b) REFERENCES shop_a.codes (a, b))")) {
            asMysqlRoot(statement);
        }
    }

    @AfterAll
    static void stop() {
        postgres.stop();
        mysql.stop();
    }

    private static void asMysqlRoot(String statement) throws IOException, InterruptedException {
        var result = mysql.execInContainer("mysql", "-uroot", "-p" + mysql.getPassword(), "-e", statement);
        assertThat(result.getExitCode()).as(statement + result.getStderr()).isZero();
    }

    private static SchemaInfo merged(JdbcTemplate jdbc, SchemaLoader loader,
                                     List<String> schemas) {
        SchemaInfo target = new SchemaInfo();
        new MultiSchemaLoader(ReservedSchemas.fromConfig(null)).load(target, schemas, loader, jdbc);
        return target;
    }

    @Test
    void postgres_keyFromAnotherSchema_bindsToTheSchemaItNames() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));

        SchemaInfo info = merged(jdbc, new PostgresSchemaLoader(), List.of("public", "shop_b"));

        assertThat(info.getAllForwardFks().values()).extracting(SchemaInfo.FkInfo::refTable)
                .containsExactly("public.users");
    }

    @Test
    void mysql_keyFromAnotherDatabase_bindsToTheDatabaseItNames() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),
                mysql.getUsername(), mysql.getPassword()));

        SchemaInfo info = merged(jdbc, new MysqlSchemaLoader(), List.of("shop_a", "shop_b"));

        assertThat(info.getAllForwardFks().values()).extracting(SchemaInfo.FkInfo::refTable)
                .containsExactlyInAnyOrder("shop_a.users", "shop_a.codes");
    }

    @Test
    void mysql_compositeKeyFromAnotherDatabase_bindsToTheDatabaseItNames() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),
                mysql.getUsername(), mysql.getPassword()));

        SchemaInfo info = merged(jdbc, new MysqlSchemaLoader(), List.of("shop_a", "shop_b"));

        assertThat(info.getAllForwardFks().values()).filteredOn(SchemaInfo.FkInfo::isComposite)
                .singleElement().satisfies(fk -> {
                    assertThat(fk.refTable()).isEqualTo("shop_a.codes");
                    assertThat(fk.refColumns()).containsExactly("a", "b");
                });
    }

    @Test
    void mysql_keyIntoADatabaseThatIsNotLoaded_isDropped_notBoundToASameNamedTable() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),
                mysql.getUsername(), mysql.getPassword()));

        SchemaInfo info = merged(jdbc, new MysqlSchemaLoader(), List.of("shop_b"));

        assertThat(info.getAllForwardFks()).isEmpty();
    }
}
