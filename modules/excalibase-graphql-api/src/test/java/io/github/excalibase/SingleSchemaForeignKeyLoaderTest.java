package io.github.excalibase;

import io.github.excalibase.mysql.MysqlSchemaLoader;
import io.github.excalibase.postgres.PostgresSchemaLoader;
import io.github.excalibase.schema.SchemaInfo;
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
 * The one-schema foreign key readers know a single schema, so a key into any other schema (an internal one
 * included) is not read, while a key inside the schema is. The reflection the engine builds from does not
 * call them, but a loader that does must not bind such a key to a same-named table of its own schema.
 */
class SingleSchemaForeignKeyLoaderTest {

    private static final List<String> POSTGRES_FIXTURE = List.of(
            "CREATE SCHEMA excalibase_meta",
            "CREATE TABLE excalibase_meta.t (id INT PRIMARY KEY)",
            "CREATE TABLE public.t (id INT PRIMARY KEY)",
            "CREATE TABLE public.items (id INT PRIMARY KEY, meta_id INT REFERENCES excalibase_meta.t (id),"
                    + " own_id INT CONSTRAINT fk_user REFERENCES public.t (id))",
            "CREATE TABLE public.notes (id INT PRIMARY KEY, own_id INT CONSTRAINT fk_user REFERENCES public.t (id))");

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("shop");

    @BeforeAll
    static void start() throws Exception {
        postgres.start();
        mysql.start();
        JdbcTemplate pg = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));
        POSTGRES_FIXTURE.forEach(pg::execute);
        for (String statement : List.of(
                "CREATE DATABASE excalibase_meta",
                "CREATE TABLE excalibase_meta.t (id INT PRIMARY KEY)",
                "CREATE TABLE shop.t (id INT PRIMARY KEY)",
                "CREATE TABLE shop.items (id INT PRIMARY KEY, meta_id INT, own_id INT,"
                        + " FOREIGN KEY (meta_id) REFERENCES excalibase_meta.t (id),"
                        + " CONSTRAINT fk_user FOREIGN KEY (own_id) REFERENCES shop.t (id))")) {
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

    @Test
    void postgres_readsAKeyInsideTheSchema_butNotOneIntoAnotherSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));
        SchemaInfo info = new SchemaInfo();

        new PostgresSchemaLoader().loadForeignKeys(jdbc, "public", info);

        assertThat(info.getAllForwardFks().values()).extracting(SchemaInfo.FkInfo::fkColumn)
                .containsOnly("own_id");
    }

    @Test
    void postgres_aConstraintNameIsUniquePerTable_soTwoTablesMayShareOne_andEachKeepsItsKey() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));
        SchemaInfo info = new SchemaInfo();

        new PostgresSchemaLoader().loadForeignKeys(jdbc, "public", info);

        assertThat(info.getAllForwardFks()).hasSize(2);
        assertThat(info.getAllForwardFks().values()).extracting(SchemaInfo.FkInfo::constraintName)
                .containsExactly("fk_user", "fk_user");
        assertThat(info.getAllReverseFks().values()).extracting(SchemaInfo.ReverseFkInfo::childTable)
                .containsExactlyInAnyOrder("items", "notes");
    }

    @Test
    void mysql_readsAKeyInsideTheSchema_butNotOneIntoAnotherDatabase() throws Exception {
        asMysqlRoot("GRANT ALL ON *.* TO 'test'@'%'");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),
                mysql.getUsername(), mysql.getPassword()));
        SchemaInfo info = new SchemaInfo();

        new MysqlSchemaLoader().loadForeignKeys(jdbc, "shop", info);

        assertThat(info.getAllForwardFks().values()).extracting(SchemaInfo.FkInfo::fkColumn)
                .containsExactly("own_id");
        assertThat(info.getAllForwardFks().values()).extracting(SchemaInfo.FkInfo::constraintName)
                .containsExactly("fk_user");
    }
}
