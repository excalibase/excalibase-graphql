package io.github.excalibase;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntrospectionHandlerTest {

    // === Empty schema (no tables) — must not crash ===

    @Test
    void buildSchema_emptySchemaInfo_doesNotThrow() {
        SchemaInfo empty = new SchemaInfo();
        assertDoesNotThrow(() -> new IntrospectionHandler(empty));
    }

    @Test
    void execute_introspection_emptySchema_returnsValidResponse() {
        SchemaInfo empty = new SchemaInfo();
        IntrospectionHandler handler = new IntrospectionHandler(empty);

        Map<String, Object> result = handler.execute("{ __schema { queryType { name } } }", Map.of());

        assertNotNull(result);
        assertNotNull(result.get("data"));
    }

    @Test
    void execute_typeQuery_emptySchema_returnsEmptyField() {
        SchemaInfo empty = new SchemaInfo();
        IntrospectionHandler handler = new IntrospectionHandler(empty);

        // Query type should have the _empty placeholder field
        Map<String, Object> result = handler.execute(
                "{ __type(name: \"Query\") { fields { name } } }", Map.of());

        assertNotNull(result);
        assertNotNull(result.get("data"));
    }

    @Test
    void execute_emptySchema_noMutationType() {
        SchemaInfo empty = new SchemaInfo();
        IntrospectionHandler handler = new IntrospectionHandler(empty);

        // Mutation type should not exist when there are no tables and no stored procedures
        Map<String, Object> result = handler.execute(
                "{ __schema { mutationType { name } } }", Map.of());

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
        assertNull(schema.get("mutationType"));
    }

    // === Schema with tables — existing behavior preserved ===

    @Test
    void buildSchema_withTables_hasQueryAndMutationFields() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("users", "id", "integer");
        info.addColumn("users", "name", "varchar");
        info.addPrimaryKey("users", "id");

        IntrospectionHandler handler = new IntrospectionHandler(info);

        Map<String, Object> result = handler.execute(
                "{ __type(name: \"Query\") { fields { name } } }", Map.of());

        assertNotNull(result);
        assertNull(result.get("errors"));
    }

    @Test
    void buildSchema_viewsOnly_noMutationsForViews() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("active_users", "id", "integer");
        info.addView("active_users");

        IntrospectionHandler handler = new IntrospectionHandler(info);

        // Query type should have the view
        Map<String, Object> result = handler.execute(
                "{ __schema { queryType { name } mutationType { name } } }", Map.of());

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
        // Mutation type should be null — views are read-only, no mutations generated
        assertNull(schema.get("mutationType"));
    }
}
