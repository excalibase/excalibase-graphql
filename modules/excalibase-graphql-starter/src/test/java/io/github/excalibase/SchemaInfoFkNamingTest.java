package io.github.excalibase;

import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests FK field naming in SchemaInfo.
 * Verifies column-based naming, multi-FK disambiguation, self-referential FKs.
 */
class SchemaInfoFkNamingTest {

    private SchemaInfo info;

    @BeforeEach
    void setUp() {
        info = new SchemaInfo();
    }

    @Nested
    @DisplayName("Single FK — column-based naming")
    class SingleFk {

        @Test
        @DisplayName("forward FK field name uses column name: user_id → userId")
        void forwardFk_usesColumnName() {
            info.addForeignKey("orders", "user_id", "users", "id");

            assertNotNull(info.getForwardFk("orders", "userId"),
                    "Forward FK field should be 'userId' (from column user_id)");
        }

        @Test
        @DisplayName("reverse FK field name uses column name from child table")
        void reverseFk_usesColumnName() {
            info.addForeignKey("orders", "customer_id", "customer", "id");

            assertNotNull(info.getReverseFk("customer", "customerId"),
                    "Reverse FK field should be 'customerId' (from child column customer_id)");
        }

        @Test
        @DisplayName("FK column without _id suffix uses raw column name")
        void fk_noIdSuffix_usesRawColumnName() {
            info.addForeignKey("tasks", "assigned_to", "employees", "id");

            assertNotNull(info.getForwardFk("tasks", "assignedTo"),
                    "Forward FK field should be 'assignedTo' (raw column name)");
        }
    }

    @Nested
    @DisplayName("Multiple FKs to same table — disambiguation")
    class MultiFk {

        @Test
        @DisplayName("two FKs to same table get distinct field names")
        void twoFksToSameTable_distinctNames() {
            info.addForeignKey("task", "assignee_id", "employee", "id");
            info.addForeignKey("task", "reporter_id", "employee", "id");

            SchemaInfo.FkInfo assigneeFk = info.getForwardFk("task", "assigneeId");
            SchemaInfo.FkInfo reporterFk = info.getForwardFk("task", "reporterId");

            assertNotNull(assigneeFk, "First FK should be 'assigneeId'");
            assertNotNull(reporterFk, "Second FK should be 'reporterId'");
            assertNotEquals(assigneeFk, reporterFk, "Two FKs should be distinct");
        }

        @Test
        @DisplayName("two FKs resolve to correct referenced columns")
        void twoFksToSameTable_correctReferences() {
            info.addForeignKey("task", "assignee_id", "employee", "id");
            info.addForeignKey("task", "reporter_id", "employee", "id");

            assertEquals("assignee_id", info.getForwardFk("task", "assigneeId").fkColumn());
            assertEquals("reporter_id", info.getForwardFk("task", "reporterId").fkColumn());
        }

        @Test
        @DisplayName("reverse FKs from multiple columns to same table are distinct")
        void reverseFks_multiColumn_distinct() {
            info.addForeignKey("task", "assignee_id", "employee", "id");
            info.addForeignKey("task", "reporter_id", "employee", "id");

            assertNotNull(info.getReverseFk("employee", "assigneeId"),
                    "Reverse FK for assignee_id should exist");
            assertNotNull(info.getReverseFk("employee", "reporterId"),
                    "Reverse FK for reporter_id should exist");
        }
    }

    @Nested
    @DisplayName("Self-referential FK")
    class SelfRefFk {

        @Test
        @DisplayName("self-ref FK: employee.manager_id → employee")
        void selfRefFk_singleColumn() {
            info.addForeignKey("employee", "manager_id", "employee", "id");

            assertNotNull(info.getForwardFk("employee", "managerId"),
                    "Forward self-ref FK should be 'managerId'");
            assertNotNull(info.getReverseFk("employee", "managerId"),
                    "Reverse self-ref FK should also exist");
        }

        @Test
        @DisplayName("two self-ref FKs: manager_id + mentor_id both to employee")
        void selfRefFk_twoColumns() {
            info.addForeignKey("employee", "manager_id", "employee", "id");
            info.addForeignKey("employee", "mentor_id", "employee", "id");

            assertNotNull(info.getForwardFk("employee", "managerId"));
            assertNotNull(info.getForwardFk("employee", "mentorId"));
            assertNotNull(info.getReverseFk("employee", "managerId"));
            assertNotNull(info.getReverseFk("employee", "mentorId"));
        }
    }

    @Nested
    @DisplayName("Schema-prefixed tables")
    class SchemaPrefixed {

        @Test
        @DisplayName("schema-prefixed FK: complex.orders.customer_id → complex.customer")
        void schemaPrefixed_fkNaming() {
            info.addForeignKey("complex.orders", "customer_id", "complex.customer", "id");

            assertNotNull(info.getForwardFk("complex.orders", "complexCustomerId"),
                    "Schema-prefixed forward FK should be 'complexCustomerId'");
        }

        @Test
        @DisplayName("cross-schema FK uses source table schema for field name")
        void crossSchema_fkNaming() {
            info.addForeignKey("sales.orders", "user_id", "public.users", "id");

            assertNotNull(info.getForwardFk("sales.orders", "salesUserId"),
                    "Cross-schema FK should use source schema prefix: 'salesUserId'");
        }
    }

    @Nested
    @DisplayName("Composite FK — table-based naming unchanged")
    class CompositeFk {

        @Test
        @DisplayName("composite FK uses table-based naming")
        void compositeFk_tableBasedNaming() {
            info.addCompositeForeignKey("child", java.util.List.of("parent_id1", "parent_id2"),
                    "parent", java.util.List.of("id1", "id2"));

            assertNotNull(info.getForwardFk("child", "parent"),
                    "Composite FK should use table-based name 'parent'");
        }
    }
}
