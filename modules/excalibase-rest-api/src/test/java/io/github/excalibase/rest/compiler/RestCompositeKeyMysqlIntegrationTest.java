package io.github.excalibase.rest.compiler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.excalibase.mysql.MysqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A composite key joins on every column pair in an embed's SQL, run on a real MySQL. (EXC-833) */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestCompositeKeyMysqlIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    private HikariDataSource dataSource;
    private NamedParameterJdbcTemplate named;
    private RestQueryCompiler compiler;

    @BeforeAll
    void setUp() {
        mysql.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(mysql.getJdbcUrl());
        dataSource.setUsername(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE parent (a INT, b INT, name VARCHAR(20) NOT NULL, PRIMARY KEY (a, b))");
        jdbc.execute("INSERT INTO parent VALUES (1, 1, 'p11'), (1, 2, 'p12'), (2, 1, 'p21')");
        jdbc.execute("CREATE TABLE line (id INT PRIMARY KEY, pa INT, pb INT,"
                + " FOREIGN KEY (pa, pb) REFERENCES parent (a, b))");
        jdbc.execute("INSERT INTO line VALUES (1, 1, 2), (2, 1, 1), (3, 2, 1)");

        SchemaInfo schema = new SchemaInfo();
        String db = mysql.getDatabaseName();
        for (String table : List.of("parent", "line")) {
            schema.setTableSchema(db + "." + table, db);
        }
        schema.addColumn(db + ".parent", "a", "int");
        schema.addColumn(db + ".parent", "b", "int");
        schema.addColumn(db + ".parent", "name", "varchar");
        schema.addColumn(db + ".line", "id", "int");
        schema.addColumn(db + ".line", "pa", "int");
        schema.addColumn(db + ".line", "pb", "int");
        schema.addPrimaryKey(db + ".parent", "a");
        schema.addPrimaryKey(db + ".parent", "b");
        schema.addPrimaryKey(db + ".line", "id");
        schema.addCompositeForeignKey(db + ".line", List.of("pa", "pb"), db + ".parent", List.of("a", "b"));
        compiler = new RestQueryCompiler(schema, new MysqlDialect(), db, 100);
    }

    @AfterAll
    void tearDown() {
        dataSource.close();
        mysql.stop();
    }

    private List<Map<String, Object>> rows(String table, List<String> columns, RestQueryCompiler.EmbedSpec embed,
                                            RestQueryCompiler.OrderBySpec order) throws Exception {
        var compiled = compiler.compileSelect(new RestQueryCompiler.SelectQuery(mysql.getDatabaseName() + "." + table,
                columns, List.of(), List.of(), List.of(embed), List.of(order), 10, 0, false));
        Object body = named.queryForMap(compiled.sql(), new MapSqlParameterSource(compiled.params())).get("body");
        return MAPPER.readValue(body.toString(), new TypeReference<>() {});
    }

    @Test
    @SuppressWarnings("unchecked")
    void forwardEmbed_returnsTheParentOfBothColumns() throws Exception {
        var lines = rows("line", List.of("id"), new RestQueryCompiler.EmbedSpec("parent", List.of("name")),
                new RestQueryCompiler.OrderBySpec("id", "ASC", null));

        assertThat(lines).extracting(line -> ((Map<String, Object>) line.get("parent")).get("name"))
                .containsExactly("p12", "p11", "p21");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reverseEmbed_listsOnlyTheChildrenOfThatParent() throws Exception {
        var parents = rows("parent", List.of("a", "b"), new RestQueryCompiler.EmbedSpec("line", List.of("id")),
                new RestQueryCompiler.OrderBySpec("name", "ASC", null));

        assertThat(parents).extracting(parent -> ((List<Map<String, Object>>) parent.get("line")).stream()
                        .map(line -> line.get("id")).toList())
                .containsExactly(List.of(2), List.of(1), List.of(3));
    }
}
