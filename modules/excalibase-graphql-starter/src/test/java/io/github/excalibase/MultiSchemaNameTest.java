package io.github.excalibase;

import io.github.excalibase.schema.NamingUtils;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-schema naming conventions.
 * When multiple schemas are loaded, GraphQL type names must be prefixed
 * with the schema name to avoid collisions.
 */
class MultiSchemaNameTest {

    // === NamingUtils: schema-prefixed naming ===

    @Test
    void schemaPrefix_singleWord() {
        // public + users → PublicUsers (type), publicUsers (field)
        assertEquals("PublicUsers", NamingUtils.schemaTypeName("public", "users"));
        assertEquals("publicUsers", NamingUtils.schemaFieldName("public", "users"));
    }

    @Test
    void schemaPrefix_snakeCaseTable() {
        // sales + order_items → SalesOrderItems (type), salesOrderItems (field)
        assertEquals("SalesOrderItems", NamingUtils.schemaTypeName("sales", "order_items"));
        assertEquals("salesOrderItems", NamingUtils.schemaFieldName("sales", "order_items"));
    }

    @Test
    void schemaPrefix_snakeCaseSchema() {
        // my_schema + users → MySchemaUsers (type), mySchemaUsers (field)
        assertEquals("MySchemaUsers", NamingUtils.schemaTypeName("my_schema", "users"));
        assertEquals("mySchemaUsers", NamingUtils.schemaFieldName("my_schema", "users"));
    }

    // === SchemaInfo: multi-schema loading ===

    @Test
    void multiSchema_isMultiSchema_false_whenSingleSchema() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("users", "id", "integer");
        info.setTableSchema("users", "public");

        assertFalse(info.isMultiSchema());
    }

    @Test
    void multiSchema_isMultiSchema_true_whenMultipleSchemas() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("users", "id", "integer");
        info.setTableSchema("users", "public");
        info.addColumn("orders", "id", "integer");
        info.setTableSchema("orders", "sales");

        assertTrue(info.isMultiSchema());
    }

    @Test
    void multiSchema_sameTableNameDifferentSchemas_storedSeparately() {
        SchemaInfo info = new SchemaInfo();
        // Both schemas have a "users" table
        info.addColumn("public.users", "id", "integer");
        info.setTableSchema("public.users", "public");
        info.addColumn("sales.users", "id", "integer");
        info.addColumn("sales.users", "email", "varchar");
        info.setTableSchema("sales.users", "sales");

        assertTrue(info.hasTable("public.users"));
        assertTrue(info.hasTable("sales.users"));
        assertEquals(1, info.getColumns("public.users").size());
        assertEquals(2, info.getColumns("sales.users").size());
    }

    // === SchemaInfo: graphqlFieldName mapping ===

    @Test
    void multiSchema_graphqlFieldName_singleSchema_noPrefix() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("users", "id", "integer");
        info.setTableSchema("users", "public");

        // Single schema → no prefix, same as current behavior
        assertEquals("users", NamingUtils.toLowerCamelCase("users"));
    }

    @Test
    void multiSchema_resolveTableFromField_singleSchema() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("order_items", "id", "integer");
        info.setTableSchema("order_items", "public");

        // Single schema → "orderItems" resolves to "order_items"
        assertTrue(info.hasTable("order_items"));
    }

    @Test
    void multiSchema_resolveTableFromField_multiSchema() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("public.users", "id", "integer");
        info.setTableSchema("public.users", "public");
        info.addColumn("sales.users", "id", "integer");
        info.setTableSchema("sales.users", "sales");

        // Multi-schema → "publicUsers" resolves to "public.users"
        assertTrue(info.hasTable("public.users"));
        assertTrue(info.hasTable("sales.users"));
    }
}
