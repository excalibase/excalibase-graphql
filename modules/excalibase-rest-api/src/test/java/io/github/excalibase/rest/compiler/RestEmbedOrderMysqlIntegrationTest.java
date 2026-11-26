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

/** An embedded list is ordered on a real MySQL, which drops an ORDER BY in a subquery that has no LIMIT. (EXC-698) */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestEmbedOrderMysqlIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCORE = "score";
    private static final String POSTS_RELATION = "posts";
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    private HikariDataSource dataSource;
    private NamedParameterJdbcTemplate named;
    private RestQueryCompiler compiler;
    private String db;

    @BeforeAll
    void setUp() {
        mysql.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(mysql.getJdbcUrl());
        dataSource.setUsername(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users (id INT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE posts (id INT PRIMARY KEY, author_id INT, score INT,"
                + " FOREIGN KEY (author_id) REFERENCES users (id))");
        jdbc.execute("INSERT INTO users VALUES (1), (2)");
        jdbc.execute("INSERT INTO posts VALUES (1, 1, 10), (2, 1, 30), (3, 1, 20), (4, 2, 5), (5, 2, 50), (6, 2, 7)");
        db = mysql.getDatabaseName();
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn(db + ".users", "id", "int");
        schema.addPrimaryKey(db + ".users", "id");
        schema.setTableSchema(db + ".users", db);
        for (String column : List.of("id", "author_id", SCORE)) {
            schema.addColumn(db + ".posts", column, "int");
        }
        schema.addPrimaryKey(db + ".posts", "id");
        schema.setTableSchema(db + ".posts", db);
        schema.addForeignKey(db + ".posts", "author_id", db + ".users", "id");
        compiler = new RestQueryCompiler(schema, new MysqlDialect(), db, 100);
    }

    @AfterAll
    void tearDown() {
        dataSource.close();
        mysql.stop();
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> scoresPerUser(Integer limit) throws Exception {
        var embed = new RestQueryCompiler.EmbedSpec(POSTS_RELATION, List.of(SCORE), null, List.of(), null,
                List.of(new RestQueryCompiler.OrderBySpec(SCORE, "DESC", null)), limit);
        var compiled = compiler.compileSelect(new RestQueryCompiler.SelectQuery(db + ".users", List.of("id"),
                List.of(), List.of(), List.of(embed), List.of(new RestQueryCompiler.OrderBySpec("id", "ASC", null)),
                10, 0, false));
        Object body = named.queryForMap(compiled.sql(), new MapSqlParameterSource(compiled.params())).get("body");
        List<Map<String, Object>> users = MAPPER.readValue(body.toString(), new TypeReference<>() {});
        return users.stream().map(user -> ((List<Map<String, Object>>) user.get(POSTS_RELATION)).stream()
                .map(post -> post.get(SCORE)).toList()).toList();
    }

    @Test
    void orderedWithNoLimit_comesBackOrdered() throws Exception {
        assertThat(scoresPerUser(null)).containsExactly(List.of(30, 20, 10), List.of(50, 7, 5));
    }

    @Test
    void limitIsPerParent() throws Exception {
        assertThat(scoresPerUser(2)).containsExactly(List.of(30, 20), List.of(50, 7));
    }
}
