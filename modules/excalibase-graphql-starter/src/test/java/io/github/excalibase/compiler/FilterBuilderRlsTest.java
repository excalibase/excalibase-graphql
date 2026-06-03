package io.github.excalibase.compiler;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.Parser;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsWhereContributor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Pins the RLS injection chokepoint in {@link FilterBuilder#buildWhereConditions}:
 * the active {@link RlsContext} contributor's predicate must be appended to every
 * table WHERE — including queries that supply no user filter — without colliding
 * with user-supplied params.
 */
@ExtendWith(MockitoExtension.class)
class FilterBuilderRlsTest {

    @Mock
    SqlDialect dialect;

    private FilterBuilder filterBuilder;

    @BeforeEach
    void setUp() {
        lenient().when(dialect.quoteIdentifier(anyString())).thenAnswer(inv -> "\"" + inv.getArgument(0) + "\"");
        filterBuilder = new FilterBuilder(dialect, 100);
    }

    @AfterEach
    void clearContext() {
        RlsContext.clear();
    }

    private Field parseField(String query) {
        Document doc = Parser.parse(query);
        OperationDefinition op = doc.getDefinitionsOfType(OperationDefinition.class).getFirst();
        for (Selection<?> selection : op.getSelectionSet().getSelections()) {
            if (selection instanceof Field f) return f;
        }
        throw new IllegalStateException("no field");
    }

    /** Contributor that restricts exactly one table with a fixed namespaced predicate. */
    private static RlsWhereContributor restrict(String table) {
        return tableName -> table.equals(tableName)
                ? new RlsWhereContributor.Contribution(
                        "\"user_id\" = :rls_c0_p0", Map.of("rls_c0_p0", "alice"))
                : null;
    }

    @Test
    @DisplayName("RLS predicate is appended even when the query has no where argument")
    void appendsRlsWithoutUserFilter() {
        RlsContext.set(restrict("orders"));
        Field field = parseField("{ orders { id } }");
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();

        filterBuilder.buildWhereConditions(field, "t", params, conditions, "orders");

        assertThat(conditions).singleElement().asString().contains("\"user_id\" = :rls_c0_p0");
        assertThat(params).containsEntry("rls_c0_p0", "alice");
    }

    @Test
    @DisplayName("RLS predicate is ANDed alongside the user filter and its params")
    void combinesWithUserFilter() {
        RlsContext.set(restrict("orders"));
        Field field = parseField("{ orders(where: { id: { eq: 1 } }) { id } }");
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();

        filterBuilder.buildWhereConditions(field, "t", params, conditions, "orders");

        assertThat(conditions).hasSize(2);
        assertThat(conditions.toString()).contains(":p_id_eq").contains(":rls_c0_p0");
        assertThat(params).containsKey("rls_c0_p0");
        assertThat(params.keySet()).anyMatch(k -> k.startsWith("p_id_eq"));
    }

    @Test
    @DisplayName("no contributor set → no RLS condition")
    void noContributor() {
        Field field = parseField("{ orders { id } }");
        List<String> conditions = new ArrayList<>();

        filterBuilder.buildWhereConditions(field, "t", new HashMap<>(), conditions, "orders");

        assertThat(conditions).isEmpty();
    }

    @Test
    @DisplayName("contributor that doesn't target this table → no RLS condition")
    void contributorForOtherTable() {
        RlsContext.set(restrict("invoices"));
        Field field = parseField("{ orders { id } }");
        List<String> conditions = new ArrayList<>();

        filterBuilder.buildWhereConditions(field, "t", new HashMap<>(), conditions, "orders");

        assertThat(conditions).isEmpty();
    }

    @Test
    @DisplayName("null table name → RLS injection skipped (no NPE)")
    void nullTableNameSkips() {
        RlsContext.set(restrict("orders"));
        Field field = parseField("{ orders { id } }");
        List<String> conditions = new ArrayList<>();

        filterBuilder.buildWhereConditions(field, "t", new HashMap<>(), conditions, null);

        assertThat(conditions).isEmpty();
    }
}
