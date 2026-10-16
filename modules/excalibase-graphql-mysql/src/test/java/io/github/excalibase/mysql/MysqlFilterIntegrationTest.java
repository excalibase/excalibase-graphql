package io.github.excalibase.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL runs regex, JSON and full-text filters natively, against a real MySQL 8.4; what it cannot run is refused
 * at compile time instead of being left out of the query.
 */
@Testcontainers
class MysqlFilterIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static NamedParameterJdbcTemplate named;
    private static SchemaInfo schema;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(),
                mysql.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE doc (
                    id    INT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(200) NOT NULL,
                    body  TEXT,
                    notes TEXT,
                    meta  JSON,
                    FULLTEXT KEY ft_body (body),
                    FULLTEXT KEY ft_pair (title, notes)
                )""");
        jdbc.execute("""
                INSERT INTO doc (title, body, notes, meta) VALUES
                    ('Alpha', 'the quick brown fox jumps', 'one', '{"a": 1, "tags": ["x"], "we.ird": true}'),
                    ('beta', 'lazy brown dogs sleep', 'two', '{"b": 2}'),
                    ('Gamma', 'quick silver fox', 'three', '{"a": 1, "b": 2}'),
                    ('delta', 'a brown fox in the snow', 'four', NULL)""");
        named = new NamedParameterJdbcTemplate(dataSource);
        Map<String, SchemaInfo> perSchema = new HashMap<>();
        new MysqlSchemaLoader().loadAll(jdbc, List.of(mysql.getDatabaseName()), perSchema);
        schema = perSchema.get(mysql.getDatabaseName());
    }

    private static SqlCompiler compiler() {
        return new SqlCompiler(schema, mysql.getDatabaseName(), 30, new MysqlDialect(), new MysqlMutationCompiler());
    }

    private static List<Integer> ids(String where) {
        return ids(where, Map.of());
    }

    private static List<Integer> ids(String where, Map<String, Object> variables) {
        SqlCompiler.CompiledQuery compiled = compiler().compile(
                "query($w: DocWhereInput) { doc(where: " + where + ", orderBy: { id: ASC }) { id } }", variables);
        String answer = named.queryForObject(compiled.sql(), compiled.params(), String.class);
        List<Integer> found = new ArrayList<>();
        try {
            for (JsonNode row : JSON.readTree(answer).get("doc")) {
                found.add(row.get("id").asInt());
            }
        } catch (Exception failure) {
            throw new IllegalStateException(answer, failure);
        }
        return found;
    }

    @Test
    void theLoaderMarksColumnsWithTheirOwnFullTextIndex() {
        assertThat(schema.isFullTextColumn("doc", "body")).isTrue();
        assertThat(schema.isFullTextColumn("doc", "notes")).isFalse();
        assertThat(schema.isFullTextColumn("doc", "title")).isFalse();
    }

    @Nested
    class Regex {
        @Test
        void regexIsCaseSensitive() {
            assertThat(ids("{ title: { regex: \"^a\" } }")).isEmpty();
            assertThat(ids("{ title: { regex: \"^A\" } }")).containsExactly(1);
        }

        @Test
        void iregexIgnoresCase() {
            assertThat(ids("{ title: { iregex: \"^(a|d)\" } }")).containsExactly(1, 4);
        }
    }

    @Nested
    class Json {
        @Test
        void containsAndContainedBy() {
            assertThat(ids("{ meta: { contains: { a: 1 } } }")).containsExactly(1, 3);
            assertThat(ids("{ meta: { containedBy: { a: 1, b: 2, c: 3 } } }")).containsExactly(2, 3);
        }

        @Test
        void keyPredicates() {
            assertThat(ids("{ meta: { hasKey: \"we.ird\" } }")).containsExactly(1);
            assertThat(ids("{ meta: { hasKeys: [\"a\", \"b\"] } }")).containsExactly(3);
            assertThat(ids("{ meta: { hasAnyKeys: [\"a\", \"b\"] } }")).containsExactly(1, 2, 3);
        }

        @Test
        void aContainsVariableIsBoundAsJson() {
            Map<String, Object> variables = Map.of("w", Map.of("meta", Map.of("contains", Map.of("b", 2))));
            assertThat(ids("$w", variables)).containsExactly(2, 3);
        }

        @Test
        void equalityOnJsonIsRefused() {
            assertThatThrownBy(() -> ids("{ meta: { eq: { b: 2 } } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'eq'");
        }
    }

    @Nested
    class FullText {
        @Test
        void searchNeedsEveryWord() {
            assertThat(ids("{ body: { search: \"quick fox\" } }")).containsExactly(1, 3);
            assertThat(ids("{ body: { search: \"the brown fox\" } }")).containsExactly(1, 4);
        }

        @Test
        void phraseSearchKeepsTheWordsTogether() {
            assertThat(ids("{ body: { phraseSearch: \"brown fox\" } }")).containsExactly(1, 4);
        }

        @Test
        void rawSearchTakesBooleanModeSyntax() {
            assertThat(ids("{ body: { rawSearch: \"brown -fox\" } }")).containsExactly(2);
        }

        @Test
        void searchSyntaxInPlainTextIsNotInterpreted() {
            assertThat(ids("{ body: { search: \"brown -fox\" } }")).containsExactly(1, 4);
        }

        @Test
        void webSearchIsRefused() {
            assertThatThrownBy(() -> ids("{ body: { webSearch: \"fox\" } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'webSearch'");
        }

        @Test
        void aColumnWithoutItsOwnFullTextIndexIsRefusedWithTheReason() {
            assertThatThrownBy(() -> ids("{ notes: { search: \"one\" } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'notes'")
                    .hasMessageContaining("FULLTEXT index");
        }
    }

    @Nested
    class Variables {
        @Test
        void aWholeWhereVariableIsApplied() {
            assertThat(ids("$w", Map.of("w", Map.of("title", Map.of("eq", "Gamma"))))).containsExactly(3);
        }
    }

    @Nested
    class Mutations {
        @Test
        void onConflictIsRefusedRatherThanIgnored() {
            assertThatThrownBy(() -> compiler().compile("mutation { createDoc(input: { title: \"x\" }, "
                    + "onConflict: { constraint: \"PRIMARY\", update_columns: [\"title\"] }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'onConflict'");
        }

        @Test
        void aDeleteThroughAWhereVariableNamesItsRows() {
            Map<String, Object> variables = Map.of("w", Map.of("id", Map.of("eq", 99)));
            SqlCompiler.CompiledQuery compiled = compiler().compile(
                    "mutation($w: DocWhereInput) { deleteDoc(where: $w) { id } }", variables);
            assertThat(compiled.write()).isInstanceOf(MysqlWrite.class);
            assertThat(compiled.params()).containsValue(99);
        }
    }
}
