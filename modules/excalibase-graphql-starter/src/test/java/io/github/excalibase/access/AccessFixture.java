package io.github.excalibase.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionSetParser;
import io.github.excalibase.permissions.RolePermissions;
import io.github.excalibase.schema.SchemaInfo;

import java.util.List;

/** customers ← orders, plus a view, a procedure and a computed field, keyed like the merged engine schema. */
final class AccessFixture {

    static final String CUSTOMERS = "public.customers";
    static final String ORDERS = "public.orders";
    static final String AUDIT = "public.audit";
    static final String ORDER_VIEW = "public.order_view";

    static final String U1 = "11111111-1111-1111-1111-111111111111";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AccessFixture() {
    }

    static SchemaInfo schema() {
        SchemaInfo schema = new SchemaInfo();
        table(schema, CUSTOMERS, "id", "integer", "name", "text", "email", "text");
        table(schema, ORDERS, "id", "integer", "customer_id", "integer", "owner_id", "uuid",
                "total", "numeric", "status", "text", "secret", "text");
        table(schema, AUDIT, "id", "integer", "note", "text");
        table(schema, ORDER_VIEW, "id", "integer", "total", "numeric");
        schema.addView(ORDER_VIEW);
        List.of(CUSTOMERS, ORDERS, AUDIT).forEach(table -> schema.addPrimaryKey(table, "id"));
        schema.addForeignKey(ORDERS, "customer_id", CUSTOMERS, "id");
        schema.addComputedField(ORDERS, "orders_label", "text");
        functions(schema);
        schema.addEnumValue("public.mood", "ok");
        return schema;
    }

    /** Routines of every shape tracking must accept or refuse. */
    private static void functions(SchemaInfo schema) {
        function(schema, "search_orders", SchemaInfo.Volatility.STABLE, true, ORDERS,
                arg("p_query", "text", false), arg("p_limit", "integer", true));
        function(schema, "place_order", SchemaInfo.Volatility.VOLATILE, true, ORDERS,
                arg("p_total", "numeric", false), arg("session", "jsonb", false));
        function(schema, "latest_order", SchemaInfo.Volatility.STABLE, false, ORDERS);
        function(schema, "order_count", SchemaInfo.Volatility.STABLE, false, null);
        function(schema, "audit_rows", SchemaInfo.Volatility.IMMUTABLE, true, AUDIT);
        function(schema, "view_rows", SchemaInfo.Volatility.IMMUTABLE, true, ORDER_VIEW);
        function(schema, "orders", SchemaInfo.Volatility.STABLE, true, ORDERS);
        function(schema, "bad_where", SchemaInfo.Volatility.STABLE, true, ORDERS, arg("where", "text", false));
        schema.addFunction("public.twice", new SchemaInfo.FunctionInfo("public", "twice",
                SchemaInfo.RoutineKind.FUNCTION, SchemaInfo.Volatility.STABLE, false, true, ORDERS, List.of(), 2));
        schema.addFunction("public.do_thing", new SchemaInfo.FunctionInfo("public", "do_thing",
                SchemaInfo.RoutineKind.PROCEDURE, SchemaInfo.Volatility.VOLATILE, false, false, null,
                List.of(arg("p", "integer", false)), 1));
    }

    private static void function(SchemaInfo schema, String name, SchemaInfo.Volatility volatility, boolean set,
                                 String returnTable, SchemaInfo.FunctionArg... args) {
        schema.addFunction("public." + name, new SchemaInfo.FunctionInfo("public", name,
                SchemaInfo.RoutineKind.FUNCTION, volatility, false, set, returnTable, List.of(args), 1));
    }

    private static SchemaInfo.FunctionArg arg(String name, String type, boolean hasDefault) {
        return new SchemaInfo.FunctionArg(name, type, "i", hasDefault);
    }

    private static void table(SchemaInfo schema, String table, String... namesAndTypes) {
        schema.setTableSchema(table, "public");
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            schema.addColumn(table, namesAndTypes[i], namesAndTypes[i + 1]);
        }
    }

    /** A role's permissions from the {@code tables} entries of a document, all for role {@code user}. */
    static RolePermissions user(String... tableEntries) {
        return document(List.of(tableEntries), List.of(), List.of()).forRole("user");
    }

    /** A whole document of {@code tables}, {@code functions} and {@code functionPermissions} entries. */
    static PermissionSet document(List<String> tables, List<String> functions, List<String> functionPermissions) {
        String document = "{\"projectId\":\"proj\",\"version\":1,\"tables\":[" + String.join(",", tables)
                + "],\"functions\":[" + String.join(",", functions)
                + "],\"functionPermissions\":[" + String.join(",", functionPermissions) + "]}";
        try {
            return PermissionSetParser.parse(MAPPER.readTree(document));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** One {@code functions} entry. */
    static String tracked(String function, String exposedAs, boolean infer, String sessionArgument) {
        return "{\"function\":\"" + function + "\",\"exposedAs\":\"" + exposedAs + "\",\"inferPermissions\":"
                + infer + ",\"sessionArgument\":" + (sessionArgument == null ? "null" : "\"" + sessionArgument + "\"")
                + "}";
    }

    /** One {@code functionPermissions} entry for role {@code user}. */
    static String callable(String function) {
        return "{\"function\":\"" + function + "\",\"role\":\"user\"}";
    }

    /** One {@code tables} entry for role {@code user}: {@code operations} is the JSON body after the names. */
    static String entry(String table, String operations) {
        return "{\"table\":\"" + table + "\",\"role\":\"user\"," + operations + "}";
    }

    static String selectAll() {
        return "\"select\":{\"filter\":{},\"columns\":\"*\"}";
    }
}
