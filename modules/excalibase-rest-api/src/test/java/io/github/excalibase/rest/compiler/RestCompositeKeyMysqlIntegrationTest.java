package io.github.excalibase.rest.compiler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.excalibase.errors.DataErrorException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        jdbc.execute("CREATE TABLE tparent (tenant INT, id INT, name VARCHAR(20) NOT NULL, PRIMARY KEY (tenant, id))");
        jdbc.execute("INSERT INTO tparent VALUES (1, 1, 'p1'), (1, 2, 'p2'), (2, 1, 'q1')");
        jdbc.execute("CREATE TABLE tchild (id INT PRIMARY KEY, tenant INT, a_id INT, b_id INT,"
                + " FOREIGN KEY (tenant, a_id) REFERENCES tparent (tenant, id),"
                + " FOREIGN KEY (tenant, b_id) REFERENCES tparent (tenant, id))");
        jdbc.execute("INSERT INTO tchild VALUES (1, 1, 1, 2), (2, 1, 2, 1)");

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
        for (String column : List.of("tenant", "id", "name")) {
            schema.addColumn(db + ".tparent", column, column.equals("name") ? "varchar" : "int");
        }
        for (String column : List.of("id", "tenant", "a_id", "b_id")) {
            schema.addColumn(db + ".tchild", column, "int");
        }
        schema.setTableSchema(db + ".tparent", db);
        schema.setTableSchema(db + ".tchild", db);
        schema.addPrimaryKey(db + ".tparent", "tenant");
        schema.addPrimaryKey(db + ".tparent", "id");
        schema.addPrimaryKey(db + ".tchild", "id");
        schema.addCompositeForeignKey(db + ".tchild", List.of("tenant", "a_id"), db + ".tparent", List.of("tenant", "id"));
        schema.addCompositeForeignKey(db + ".tchild", List.of("tenant", "b_id"), db + ".tparent", List.of("tenant", "id"));
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

    @Test
    @SuppressWarnings("unchecked")
    void keysSharingTheirFirstColumn_areToldApartByTheHintsTheErrorSuggests() throws Exception {
        var unhinted = new RestQueryCompiler.EmbedSpec("tparent", List.of("name"));
        var byId = new RestQueryCompiler.OrderBySpec("id", "ASC", null);
        List<String> idOnly = List.of("id");
        var ambiguous = assertThrows(DataErrorException.class, () -> rows("tchild", idOnly, unhinted, byId));
        assertThat(ambiguous.getMessage()).contains("tparent!a_id", "tparent!b_id").doesNotContain("tparent!tenant");

        var viaA = rows("tchild", List.of("id"), new RestQueryCompiler.EmbedSpec("tparent", List.of("name"), "a_id"),
                new RestQueryCompiler.OrderBySpec("id", "ASC", null));
        var viaB = rows("tchild", List.of("id"), new RestQueryCompiler.EmbedSpec("tparent", List.of("name"), "b_id"),
                new RestQueryCompiler.OrderBySpec("id", "ASC", null));
        assertThat(viaA).extracting(row -> ((Map<String, Object>) row.get("tparent")).get("name"))
                .containsExactly("p1", "p2");
        assertThat(viaB).extracting(row -> ((Map<String, Object>) row.get("tparent")).get("name"))
                .containsExactly("p2", "p1");
    }
}
