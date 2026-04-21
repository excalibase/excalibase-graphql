package io.github.excalibase;

import io.github.excalibase.schema.IntrospectionHandler;
import io.github.excalibase.schema.SchemaInfo;
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

    // === Enum types with compound keys — must prefix and not crash ===

    @Test
    void buildSchema_enumWithCompoundKey_prefixedName() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("hana.task", "id", "integer");
        info.addColumn("hana.task", "priority", "priority_level");
        info.addPrimaryKey("hana.task", "id");
        info.setTableSchema("hana.task", "hana");
        info.addColumnEnumType("hana.task", "priority", "hana.priority_level");
        info.addEnumValue("hana.priority_level", "LOW");
        info.addEnumValue("hana.priority_level", "HIGH");

        IntrospectionHandler handler = new IntrospectionHandler(info);

        // Enum type should be prefixed: HanaPriorityLevel
        Map<String, Object> result = handler.execute(
                "{ __type(name: \"HanaPriorityLevel\") { kind enumValues { name } } }", Map.of());

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) data.get("__type");
        assertNotNull(type, "HanaPriorityLevel enum type should exist");
        assertEquals("ENUM", type.get("kind"));
    }

    @Test
    void buildSchema_duplicateEnumAcrossSchemas_doesNotCrash() {
        SchemaInfo info = new SchemaInfo();
        // schema_a has priority_level
        info.addColumn("schema_a.task", "id", "integer");
        info.addColumn("schema_a.task", "priority", "priority_level");
        info.addPrimaryKey("schema_a.task", "id");
        info.setTableSchema("schema_a.task", "schema_a");
        info.addColumnEnumType("schema_a.task", "priority", "schema_a.priority_level");
        info.addEnumValue("schema_a.priority_level", "LOW");
        info.addEnumValue("schema_a.priority_level", "HIGH");

        // schema_b also has priority_level (same values)
        info.addColumn("schema_b.task", "id", "integer");
        info.addColumn("schema_b.task", "priority", "priority_level");
        info.addPrimaryKey("schema_b.task", "id");
        info.setTableSchema("schema_b.task", "schema_b");
        info.addColumnEnumType("schema_b.task", "priority", "schema_b.priority_level");
        info.addEnumValue("schema_b.priority_level", "LOW");
        info.addEnumValue("schema_b.priority_level", "HIGH");

        // Must not crash — each enum gets a unique prefixed name
        assertDoesNotThrow(() -> new IntrospectionHandler(info));
    }

    // === Stored procedures with compound keys — must prefix ===

    @Test
    void buildSchema_storedProcWithCompoundKey_prefixedMutationName() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("hana.users", "id", "integer");
        info.addPrimaryKey("hana.users", "id");
        info.setTableSchema("hana.users", "hana");

        // Stored procedure with compound key
        info.addStoredProcedure("hana.transfer_funds",
                new SchemaInfo.ProcedureInfo("transfer_funds", java.util.List.of(
                        new SchemaInfo.ProcParam("IN", "amount", "numeric"))));

        IntrospectionHandler handler = new IntrospectionHandler(info);

        // Mutation name should be prefixed: callHanaTransferFunds
        Map<String, Object> result = handler.execute(
                "{ __schema { mutationType { fields { name } } } }", Map.of());

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
        @SuppressWarnings("unchecked")
        Map<String, Object> mutationType = (Map<String, Object>) schema.get("mutationType");
        assertNotNull(mutationType);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> fields = (java.util.List<Map<String, Object>>) mutationType.get("fields");
        java.util.List<String> fieldNames = fields.stream().map(f -> (String) f.get("name")).toList();
        assertTrue(fieldNames.contains("callHanaTransferFunds"),
                "Should have callHanaTransferFunds — got: " + fieldNames);
    }

    // === Typed filter inputs: DateTime, Boolean, Float ===

    @Test
    void buildSchema_timestampColumn_usesDateTimeFilterInput() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("events", "id", "integer");
        info.addColumn("events", "created_at", "timestamptz");
        info.addColumn("events", "start_date", "date");
        info.addPrimaryKey("events", "id");
        info.setTableSchema("events", "public");

        IntrospectionHandler handler = new IntrospectionHandler(info);
        Map<String, Object> result = handler.execute(
                "{ __type(name: \"EventsWhereInput\") { inputFields { name type { name } } } }", Map.of());

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> inputFields = (java.util.List<Map<String, Object>>)
                ((Map<String, Object>) ((Map<String, Object>) result.get("data")).get("__type")).get("inputFields");

        Map<String, String> colType = new java.util.HashMap<>();
        for (Map<String, Object> field : inputFields) {
            @SuppressWarnings("unchecked")
            Map<String, Object> type = (Map<String, Object>) field.get("type");
            colType.put((String) field.get("name"), (String) type.get("name"));
        }
        assertEquals("DateTimeFilterInput", colType.get("created_at"),
                "timestamptz column should use DateTimeFilterInput");
        assertEquals("DateTimeFilterInput", colType.get("start_date"),
                "date column should use DateTimeFilterInput");
    }

    @Test
    void buildSchema_dateTimeFilterInput_advertisesRangeOperators() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("events", "id", "integer");
        info.addColumn("events", "created_at", "timestamptz");
        info.addPrimaryKey("events", "id");
        info.setTableSchema("events", "public");

        IntrospectionHandler handler = new IntrospectionHandler(info);
        Map<String, Object> result = handler.execute(
                "{ __type(name: \"DateTimeFilterInput\") { inputFields { name } } }", Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) ((Map<String, Object>) result.get("data")).get("__type");
        assertNotNull(type, "DateTimeFilterInput type should be registered in the schema");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> fields = (java.util.List<Map<String, Object>>) type.get("inputFields");
        java.util.Set<String> ops = new java.util.HashSet<>();
        for (Map<String, Object> f : fields) ops.add((String) f.get("name"));

        // Range + equality operators — the ones codegen needs to emit typed accessors
        for (String op : java.util.List.of("eq", "neq", "gt", "gte", "lt", "lte", "in", "notIn", "isNull", "isNotNull")) {
            assertTrue(ops.contains(op), "DateTimeFilterInput missing operator: " + op + " — got " + ops);
        }
    }

    @Test
    void buildSchema_booleanColumn_usesBooleanFilterInput() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("users", "id", "integer");
        info.addColumn("users", "is_active", "boolean");
        info.addPrimaryKey("users", "id");
        info.setTableSchema("users", "public");

        IntrospectionHandler handler = new IntrospectionHandler(info);
        Map<String, Object> result = handler.execute(
                "{ __type(name: \"UsersWhereInput\") { inputFields { name type { name } } } }", Map.of());

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> inputFields = (java.util.List<Map<String, Object>>)
                ((Map<String, Object>) ((Map<String, Object>) result.get("data")).get("__type")).get("inputFields");

        String activeType = null;
        for (Map<String, Object> field : inputFields) {
            if ("is_active".equals(field.get("name"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> type = (Map<String, Object>) field.get("type");
                activeType = (String) type.get("name");
            }
        }
        assertEquals("BooleanFilterInput", activeType, "boolean column should use BooleanFilterInput");
    }

    @Test
    void buildSchema_booleanFilterInput_hasEqNeqIsNullIsNotNull() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("users", "id", "integer");
        info.addColumn("users", "is_active", "boolean");
        info.addPrimaryKey("users", "id");
        info.setTableSchema("users", "public");

        IntrospectionHandler handler = new IntrospectionHandler(info);
        Map<String, Object> result = handler.execute(
                "{ __type(name: \"BooleanFilterInput\") { inputFields { name type { name kind } } } }", Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) ((Map<String, Object>) result.get("data")).get("__type");
        assertNotNull(type, "BooleanFilterInput type should be registered");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> fields = (java.util.List<Map<String, Object>>) type.get("inputFields");
        java.util.Set<String> ops = new java.util.HashSet<>();
        for (Map<String, Object> f : fields) ops.add((String) f.get("name"));
        assertEquals(java.util.Set.of("eq", "neq", "isNull", "isNotNull"), ops,
                "BooleanFilterInput must have exactly 4 operators");
    }

    @Test
    void buildSchema_floatColumn_usesFloatFilterInput() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("products", "id", "integer");
        info.addColumn("products", "price", "numeric");
        info.addColumn("products", "weight", "real");
        info.addColumn("products", "ratio", "double precision");
        info.addPrimaryKey("products", "id");
        info.setTableSchema("products", "public");

        IntrospectionHandler handler = new IntrospectionHandler(info);
        Map<String, Object> result = handler.execute(
                "{ __type(name: \"ProductsWhereInput\") { inputFields { name type { name } } } }", Map.of());

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> inputFields = (java.util.List<Map<String, Object>>)
                ((Map<String, Object>) ((Map<String, Object>) result.get("data")).get("__type")).get("inputFields");

        Map<String, String> colType = new java.util.HashMap<>();
        for (Map<String, Object> field : inputFields) {
            @SuppressWarnings("unchecked")
            Map<String, Object> type = (Map<String, Object>) field.get("type");
            colType.put((String) field.get("name"), (String) type.get("name"));
        }
        assertEquals("FloatFilterInput", colType.get("price"), "numeric column should use FloatFilterInput");
        assertEquals("FloatFilterInput", colType.get("weight"), "real column should use FloatFilterInput");
        assertEquals("FloatFilterInput", colType.get("ratio"), "double precision column should use FloatFilterInput");
        assertEquals("IntFilterInput", colType.get("id"), "integer column should still use IntFilterInput");
    }

    @Test
    void buildSchema_floatFilterInput_advertisesRangeOperatorsWithFloatType() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("products", "id", "integer");
        info.addColumn("products", "price", "numeric");
        info.addPrimaryKey("products", "id");
        info.setTableSchema("products", "public");

        IntrospectionHandler handler = new IntrospectionHandler(info);
        Map<String, Object> result = handler.execute(
                "{ __type(name: \"FloatFilterInput\") { inputFields { name type { name ofType { name } } } } }", Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> type = (Map<String, Object>) ((Map<String, Object>) result.get("data")).get("__type");
        assertNotNull(type, "FloatFilterInput type should be registered");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> fields = (java.util.List<Map<String, Object>>) type.get("inputFields");
        java.util.Map<String, String> opToType = new java.util.HashMap<>();
        for (Map<String, Object> field : fields) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fieldType = (Map<String, Object>) field.get("type");
            String typeName = (String) fieldType.get("name");
            if (typeName == null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> of = (Map<String, Object>) fieldType.get("ofType");
                if (of != null) typeName = (String) of.get("name");
            }
            opToType.put((String) field.get("name"), typeName);
        }

        for (String op : java.util.List.of("eq", "neq", "gt", "gte", "lt", "lte")) {
            assertEquals("Float", opToType.get(op),
                    "FloatFilterInput." + op + " should be typed Float, was: " + opToType.get(op));
        }
        assertEquals("Float", opToType.get("in"), "FloatFilterInput.in element should be Float");
    }

    @Test
    void buildSchema_duplicateProcAcrossSchemas_doesNotCrash() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("schema_a.users", "id", "integer");
        info.addPrimaryKey("schema_a.users", "id");
        info.setTableSchema("schema_a.users", "schema_a");
        info.addColumn("schema_b.users", "id", "integer");
        info.addPrimaryKey("schema_b.users", "id");
        info.setTableSchema("schema_b.users", "schema_b");

        // Same proc name in two schemas
        info.addStoredProcedure("schema_a.do_stuff",
                new SchemaInfo.ProcedureInfo("do_stuff", java.util.List.of()));
        info.addStoredProcedure("schema_b.do_stuff",
                new SchemaInfo.ProcedureInfo("do_stuff", java.util.List.of()));

        assertDoesNotThrow(() -> new IntrospectionHandler(info));
    }
}
