package io.github.excalibase.permissions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The engine read endpoint's document (permissions contract, "Engine read endpoint"). */
class PermissionSetParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String CONTRACT_EXAMPLE = """
            {
              "projectId": "proj-abc",
              "version": 42,
              "tables": [
                {
                  "table": "public.orders",
                  "role": "user",
                  "select": { "filter": {"owner_id": {"_eq": "X-Excalibase-User-Id"}}, "columns": ["id","owner_id","total"], "limit": 100, "allowAggregations": false },
                  "insert": { "check": {}, "columns": "*", "set": {"owner_id": "X-Excalibase-User-Id"} },
                  "update": { "filter": {}, "check": {}, "columns": ["status"], "set": {} },
                  "delete": { "filter": {} }
                }
              ],
              "functions": [
                { "function": "public.search_orders", "exposedAs": "QUERY", "inferPermissions": true, "sessionArgument": null }
              ],
              "functionPermissions": [
                { "function": "public.search_orders", "role": "editor" }
              ]
            }
            """;

    private static ObjectNode example() {
        try {
            return (ObjectNode) MAPPER.readTree(CONTRACT_EXAMPLE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PermissionSet parse(ObjectNode document) {
        return PermissionSetParser.parse(document);
    }

    private static ObjectNode tableEntry(ObjectNode document) {
        return (ObjectNode) document.withArray("tables").get(0);
    }

    @Test
    void contractExample_mapsEveryField() {
        PermissionSet permissions = parse(example());

        assertThat(permissions.projectId()).isEqualTo("proj-abc");
        assertThat(permissions.version()).isEqualTo(42L);
        TablePermissions orders = permissions.tables().getFirst();
        assertThat(orders.table()).isEqualTo("public.orders");
        assertThat(orders.role()).isEqualTo("user");

        SelectPermission select = orders.select().orElseThrow();
        assertThat(select.filter()).isEqualTo(new ColumnCompare("owner_id", ComparisonOperator.EQ,
                new SessionVariable("x-excalibase-user-id")));
        assertThat(select.columns()).isEqualTo(ColumnSet.of(List.of("id", "owner_id", "total")));
        assertThat(select.limit()).isEqualTo(100);
        assertThat(select.allowAggregations()).isFalse();

        InsertPermission insert = orders.insert().orElseThrow();
        assertThat(insert.check()).isEqualTo(BoolExp.TRUE);
        assertThat(insert.columns()).isEqualTo(ColumnSet.ALL);
        assertThat(insert.set()).containsExactly(Map.entry("owner_id", new SessionVariable("x-excalibase-user-id")));

        UpdatePermission update = orders.update().orElseThrow();
        assertThat(update.filter()).isEqualTo(BoolExp.TRUE);
        assertThat(update.check()).isEqualTo(BoolExp.TRUE);
        assertThat(update.columns().permits("status")).isTrue();
        assertThat(update.columns().permits("total")).isFalse();
        assertThat(update.set()).isEmpty();

        assertThat(orders.delete().orElseThrow().filter()).isEqualTo(BoolExp.TRUE);

        assertThat(permissions.functions()).containsExactly(new TrackedFunction(
                "public.search_orders", TrackedFunction.ExposedAs.QUERY, true, null));
        assertThat(permissions.functionPermissions()).containsExactly(
                new FunctionPermission("public.search_orders", "editor"));
    }

    @Test
    void absentOperation_isEmpty() {
        ObjectNode document = example();
        tableEntry(document).remove(List.of("insert", "update", "delete"));

        TablePermissions orders = parse(document).tables().getFirst();

        assertThat(orders.select()).isPresent();
        assertThat(orders.insert()).isEmpty();
        assertThat(orders.update()).isEmpty();
        assertThat(orders.delete()).isEmpty();
    }

    @Test
    void nullLimitAndMissingOptionalFields_takeTheirDefaults() {
        ObjectNode document = example();
        ObjectNode select = (ObjectNode) tableEntry(document).get("select");
        select.putNull("limit");
        select.remove("allowAggregations");
        ((ObjectNode) tableEntry(document).get("insert")).remove("set");

        TablePermissions orders = parse(document).tables().getFirst();

        assertThat(orders.select().orElseThrow().limit()).isNull();
        assertThat(orders.select().orElseThrow().allowAggregations()).isFalse();
        assertThat(orders.insert().orElseThrow().set()).isEmpty();
    }

    @Test
    void presets_keepLiteralsOfEveryJsonShape() {
        ObjectNode document = example();
        ObjectNode set = (ObjectNode) tableEntry(document).get("insert").get("set");
        set.put("status", "new");
        set.put("priority", 3);
        set.put("flag", true);
        set.putNull("note");
        set.putObject("meta").put("source", "api");
        set.putArray("tags").add("a").add("b");

        Map<String, PresetValue> presets = parse(document).tables().getFirst().insert().orElseThrow().set();

        assertThat(presets.get("status")).isEqualTo(new Literal("new"));
        assertThat(presets.get("priority")).isEqualTo(new Literal(3L));
        assertThat(presets.get("flag")).isEqualTo(new Literal(true));
        assertThat(presets.get("note")).isEqualTo(new Literal(null));
        assertThat(presets.get("meta")).isEqualTo(new Literal(Map.of("source", "api")));
        assertThat(presets.get("tags")).isEqualTo(new Literal(List.of("a", "b")));
    }

    @Test
    void emptyDocument_isAValidDocumentThatPermitsNothing() {
        ObjectNode document = example();
        document.putArray("tables");
        document.putArray("functions");
        document.putArray("functionPermissions");

        PermissionSet permissions = parse(document);

        assertThat(permissions.tables()).isEmpty();
        assertThat(permissions.forRole("user").tables()).isEmpty();
    }

    @Test
    void mutationFunctionWithSessionArgument_isRead() {
        ObjectNode document = example();
        ObjectNode function = (ObjectNode) document.withArray("functions").get(0);
        function.put("exposedAs", "MUTATION").put("inferPermissions", false).put("sessionArgument", "hasura_session");

        assertThat(parse(document).functions()).containsExactly(new TrackedFunction(
                "public.search_orders", TrackedFunction.ExposedAs.MUTATION, false, "hasura_session"));
    }

    static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                invalid("projectId missing", doc -> doc.without("projectId")),
                invalid("projectId blank", doc -> doc.put("projectId", " ")),
                invalid("version missing", doc -> doc.without("version")),
                invalid("version not a number", doc -> doc.put("version", "42")),
                invalid("version negative", doc -> doc.put("version", -1)),
                invalid("tables missing", doc -> doc.without("tables")),
                invalid("tables null", doc -> doc.putNull("tables")),
                invalid("functions missing", doc -> doc.without("functions")),
                invalid("functionPermissions missing", doc -> doc.without("functionPermissions")),
                invalid("unknown top-level key", doc -> doc.put("enforced", true)),
                invalid("table unqualified", doc -> table(doc).put("table", "orders")),
                invalid("table mixed case", doc -> table(doc).put("table", "public.Orders")),
                invalid("role missing", doc -> table(doc).without("role")),
                invalid("role invalid", doc -> table(doc).put("role", "Admin")),
                invalid("role service", doc -> table(doc).put("role", "service")),
                invalid("unknown table key", doc -> table(doc).put("truncate", true)),
                invalid("operation not an object", doc -> table(doc).put("delete", true)),
                invalid("operation null", doc -> table(doc).putNull("delete")),
                invalid("duplicate table and role", doc -> doc.withArray("tables").add(table(doc).deepCopy())),
                invalid("select filter missing", doc -> operation(doc, "select").without("filter")),
                invalid("select columns missing", doc -> operation(doc, "select").without("columns")),
                invalid("select columns a bad string", doc -> operation(doc, "select").put("columns", "id")),
                invalid("select column name invalid", doc -> operation(doc, "select").putArray("columns").add("a b")),
                invalid("select column duplicated", doc -> operation(doc, "select").putArray("columns").add("id").add("id")),
                invalid("select column not a string", doc -> operation(doc, "select").putArray("columns").add(1)),
                invalid("select limit zero", doc -> operation(doc, "select").put("limit", 0)),
                invalid("select limit fractional", doc -> operation(doc, "select").put("limit", 1.5)),
                invalid("select limit a string", doc -> operation(doc, "select").put("limit", "10")),
                invalid("select allowAggregations a string", doc -> operation(doc, "select").put("allowAggregations", "yes")),
                invalid("select unknown key", doc -> operation(doc, "select").put("offset", 1)),
                invalid("select filter invalid", doc -> operation(doc, "select").putObject("filter").put("a", 1)),
                invalid("insert check missing", doc -> operation(doc, "insert").without("check")),
                invalid("insert set not an object", doc -> operation(doc, "insert").put("set", "x")),
                invalid("insert set bad column", doc -> operation(doc, "insert").putObject("set").put("a-b", 1)),
                invalid("insert set bad variable", doc -> operation(doc, "insert").putObject("set").put("a", "X-Excalibase-")),
                invalid("insert has a filter", doc -> operation(doc, "insert").putObject("filter")),
                invalid("update filter missing", doc -> operation(doc, "update").without("filter")),
                invalid("update check missing", doc -> operation(doc, "update").without("check")),
                invalid("update columns missing", doc -> operation(doc, "update").without("columns")),
                invalid("delete filter missing", doc -> operation(doc, "delete").without("filter")),
                invalid("delete has columns", doc -> operation(doc, "delete").put("columns", "*")),
                invalid("function unqualified", doc -> function(doc).put("function", "search_orders")),
                invalid("function exposedAs unknown", doc -> function(doc).put("exposedAs", "SUBSCRIPTION")),
                invalid("function inferPermissions missing", doc -> function(doc).without("inferPermissions")),
                invalid("function sessionArgument invalid", doc -> function(doc).put("sessionArgument", "a b")),
                invalid("function duplicated", doc -> doc.withArray("functions").add(function(doc).deepCopy())),
                invalid("function unknown key", doc -> function(doc).put("volatility", "STABLE")),
                invalid("function permission for an untracked function",
                        doc -> functionPermission(doc).put("function", "public.other")),
                invalid("function permission role service", doc -> functionPermission(doc).put("role", "service")),
                invalid("function permission duplicated",
                        doc -> doc.withArray("functionPermissions").add(functionPermission(doc).deepCopy())),
                invalid("table entry not an object", doc -> doc.withArray("tables").add(1)));
    }

    private static Arguments invalid(String reason, Consumer<ObjectNode> breakIt) {
        return Arguments.of(reason, breakIt);
    }

    private static ObjectNode table(ObjectNode document) {
        return tableEntry(document);
    }

    private static ObjectNode operation(ObjectNode document, String operation) {
        return (ObjectNode) tableEntry(document).get(operation);
    }

    private static ObjectNode function(ObjectNode document) {
        return (ObjectNode) document.withArray("functions").get(0);
    }

    private static ObjectNode functionPermission(ObjectNode document) {
        return (ObjectNode) document.withArray("functionPermissions").get(0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void invalidDocument_isRefusedWhole(String reason, Consumer<ObjectNode> breakIt) {
        ObjectNode document = example();
        breakIt.accept(document);

        assertThatThrownBy(() -> parse(document)).isInstanceOf(PermissionDocumentException.class);
    }

    @Test
    void notAnObject_isRefused() {
        assertThatThrownBy(() -> PermissionSetParser.parse(MAPPER.createArrayNode()))
                .isInstanceOf(PermissionDocumentException.class);
        assertThatThrownBy(() -> PermissionSetParser.parse(null))
                .isInstanceOf(PermissionDocumentException.class);
    }

    @Test
    void refusalMessage_namesWhereTheDocumentIsWrong() {
        ObjectNode document = example();
        operation(document, "select").put("limit", 0);

        assertThatThrownBy(() -> parse(document))
                .isInstanceOf(PermissionDocumentException.class)
                .hasMessageContaining("tables[0].select.limit");
    }

    @Test
    void tooManyNodesAcrossOneExpression_isRefusedWithTheLocation() {
        ObjectNode document = example();
        ObjectNode filter = operation(document, "update").putObject("filter");
        ArrayNode branches = filter.putArray("_or");
        IntStream.range(0, 200).forEach(index -> branches.addObject().putObject("a").put("_eq", 1));

        assertThatThrownBy(() -> parse(document))
                .isInstanceOf(PermissionDocumentException.class)
                .hasMessageContaining("tables[0].update.filter");
    }
}
