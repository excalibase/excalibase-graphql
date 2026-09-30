package io.github.excalibase.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        schema.addStoredProcedure("public.do_thing",
                new SchemaInfo.ProcedureInfo("do_thing", List.of(new SchemaInfo.ProcParam("IN", "p", "integer"))));
        schema.addEnumValue("public.mood", "ok");
        return schema;
    }

    private static void table(SchemaInfo schema, String table, String... namesAndTypes) {
        schema.setTableSchema(table, "public");
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            schema.addColumn(table, namesAndTypes[i], namesAndTypes[i + 1]);
        }
    }

    /** A role's permissions from the {@code tables} entries of a document, all for role {@code user}. */
    static RolePermissions user(String... tableEntries) {
        String document = "{\"projectId\":\"proj\",\"version\":1,\"tables\":[" + String.join(",", tableEntries)
                + "],\"functions\":[],\"functionPermissions\":[]}";
        try {
            return PermissionSetParser.parse(MAPPER.readTree(document)).forRole("user");
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** One {@code tables} entry for role {@code user}: {@code operations} is the JSON body after the names. */
    static String entry(String table, String operations) {
        return "{\"table\":\"" + table + "\",\"role\":\"user\"," + operations + "}";
    }

    static String selectAll() {
        return "\"select\":{\"filter\":{},\"columns\":\"*\"}";
    }
}
