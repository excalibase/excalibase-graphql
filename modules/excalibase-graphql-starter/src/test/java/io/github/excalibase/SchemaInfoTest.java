package io.github.excalibase;

import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchemaInfoTest {

    private SchemaInfo schemaInfo;

    @BeforeEach
    void setUp() {
        schemaInfo = new SchemaInfo();
    }

    // === setTableSchema / getTableSchema ===

    @Test
    void getTableSchema_returnsNull_whenNotSet() {
        schemaInfo.addColumn("users", "id", "integer");
        assertNull(schemaInfo.getTableSchema("users"));
    }

    @Test
    void getTableSchema_returnsSchemaName_whenSet() {
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.setTableSchema("users", "public");
        assertEquals("public", schemaInfo.getTableSchema("users"));
    }

    @Test
    void getTableSchema_tracksMultipleSchemas() {
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.setTableSchema("users", "public");

        schemaInfo.addColumn("orders", "id", "integer");
        schemaInfo.setTableSchema("orders", "sales");

        assertEquals("public", schemaInfo.getTableSchema("users"));
        assertEquals("sales", schemaInfo.getTableSchema("orders"));
    }

    @Test
    void setTableSchema_overwritesPreviousValue() {
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.setTableSchema("users", "public");
        schemaInfo.setTableSchema("users", "tenant_a");

        assertEquals("tenant_a", schemaInfo.getTableSchema("users"));
    }

    // === resolveSchema ===

    @Test
    void resolveSchema_returnsTableSchema_whenSet() {
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.setTableSchema("users", "sales");

        assertEquals("sales", schemaInfo.resolveSchema("users", "public"));
    }

    @Test
    void resolveSchema_returnsDefault_whenNotSet() {
        schemaInfo.addColumn("users", "id", "integer");
        // no setTableSchema call

        assertEquals("public", schemaInfo.resolveSchema("users", "public"));
    }

    @Test
    void resolveSchema_returnsDefault_forUnknownTable() {
        assertEquals("dvdrental", schemaInfo.resolveSchema("nonexistent", "dvdrental"));
    }

    // === clearAll clears tableSchema ===

    @Test
    void clearAll_clearsTableSchema() {
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.setTableSchema("users", "public");

        schemaInfo.clearAll();

        assertNull(schemaInfo.getTableSchema("users"));
        assertTrue(schemaInfo.getTableNames().isEmpty());
    }

    // === Existing functionality still works ===

    @Test
    void addColumn_stillTracksColumnsAndTypes() {
        schemaInfo.addColumn("users", "id", "integer");
        schemaInfo.addColumn("users", "name", "varchar");

        assertTrue(schemaInfo.hasTable("users"));
        assertEquals(2, schemaInfo.getColumns("users").size());
        assertEquals("integer", schemaInfo.getColumnType("users", "id"));
        assertEquals("varchar", schemaInfo.getColumnType("users", "name"));
    }
}
