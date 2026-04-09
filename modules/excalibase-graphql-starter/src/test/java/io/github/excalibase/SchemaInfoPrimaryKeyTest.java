package io.github.excalibase;

import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests hasPrimaryKey() and getPrimaryKeys() behavior,
 * including tables without PKs and composite PKs.
 */
class SchemaInfoPrimaryKeyTest {

    private SchemaInfo info;

    @BeforeEach
    void setUp() {
        info = new SchemaInfo();
    }

    @Test
    @DisplayName("hasPrimaryKey returns true for table with single PK")
    void hasPrimaryKey_singlePk_returnsTrue() {
        info.addPrimaryKey("users", "id");
        assertTrue(info.hasPrimaryKey("users"));
    }

    @Test
    @DisplayName("hasPrimaryKey returns true for table with composite PK")
    void hasPrimaryKey_compositePk_returnsTrue() {
        info.addPrimaryKey("order_items", "order_id");
        info.addPrimaryKey("order_items", "product_id");
        assertTrue(info.hasPrimaryKey("order_items"));
        assertEquals(List.of("order_id", "product_id"), info.getPrimaryKeys("order_items"));
    }

    @Test
    @DisplayName("hasPrimaryKey returns false for table without PK")
    void hasPrimaryKey_noPk_returnsFalse() {
        // Table exists (has columns) but no PK added
        info.addColumn("audit_log", "event_type", "varchar");
        info.addColumn("audit_log", "payload", "jsonb");
        assertFalse(info.hasPrimaryKey("audit_log"));
    }

    @Test
    @DisplayName("hasPrimaryKey returns false for unknown table")
    void hasPrimaryKey_unknownTable_returnsFalse() {
        assertFalse(info.hasPrimaryKey("nonexistent"));
    }

    @Test
    @DisplayName("getPrimaryKeys falls back to 'id' for table without PK")
    void getPrimaryKeys_noPk_fallsBackToId() {
        assertEquals(List.of("id"), info.getPrimaryKeys("no_pk_table"));
    }

    @Test
    @DisplayName("getPrimaryKey returns first PK column")
    void getPrimaryKey_returnsFirst() {
        info.addPrimaryKey("order_items", "order_id");
        info.addPrimaryKey("order_items", "product_id");
        assertEquals("order_id", info.getPrimaryKey("order_items"));
    }

    @Test
    @DisplayName("schema-prefixed table hasPrimaryKey works")
    void hasPrimaryKey_schemaPrefixed() {
        info.addPrimaryKey("complex.employee", "id");
        assertTrue(info.hasPrimaryKey("complex.employee"));
        assertFalse(info.hasPrimaryKey("complex.audit_log"));
    }
}
