package io.github.excalibase;

import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
        @DisplayName("reverse FK field name uses child table name")
        void reverseFk_usesColumnName() {
            info.addForeignKey("orders", "customer_id", "customer", "id");

            assertNotNull(info.getReverseFk("customer", "orders"),
                    "Reverse FK field should be 'orders' (child table name)");
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
        @DisplayName("reverse FKs from multiple columns to same table are distinct — disambiguated by column suffix")
        void reverseFks_multiColumn_distinct() {
            info.addForeignKey("task", "assignee_id", "employee", "id");
            info.addForeignKey("task", "reporter_id", "employee", "id");

            assertNotNull(info.getReverseFk("employee", "taskAssigneeId"),
                    "Reverse FK for assignee_id should be 'taskAssigneeId'");
            assertNotNull(info.getReverseFk("employee", "taskReporterId"),
                    "Reverse FK for reporter_id should be 'taskReporterId'");
        }
    }

    @Nested
    @DisplayName("Reverse names do not depend on the order keys are added in")
    class OrderIndependent {

        private SchemaInfo matchesWith(String... columnsInOrder) {
            SchemaInfo schema = new SchemaInfo();
            for (String column : columnsInOrder) {
                schema.addForeignKey("matches", column, "teams", "id");
            }
            return schema;
        }

        @Test
        @DisplayName("three keys to one table are each named for their column, in any order")
        void threeKeys_sameNamesInAnyOrder() {
            SchemaInfo first = matchesWith("home_team_id", "away_team_id", "winner_team_id");
            SchemaInfo second = matchesWith("winner_team_id", "home_team_id", "away_team_id");

            assertEquals(Set.of("teams.matchesHomeTeamId", "teams.matchesAwayTeamId",
                    "teams.matchesWinnerTeamId"), first.getAllReverseFks().keySet());
            assertEquals(first.getAllReverseFks(), second.getAllReverseFks());
        }

        @Test
        @DisplayName("a composite key beside a single-column key to one table: both kept, named for their columns")
        void compositeBesideSingle_bothKept() {
            SchemaInfo compositeFirst = new SchemaInfo();
            compositeFirst.addCompositeForeignKey("matches", List.of("league", "year"),
                    "seasons", List.of("league", "year"));
            compositeFirst.addForeignKey("matches", "replay_of_season_id", "seasons", "id");
            SchemaInfo singleFirst = new SchemaInfo();
            singleFirst.addForeignKey("matches", "replay_of_season_id", "seasons", "id");
            singleFirst.addCompositeForeignKey("matches", List.of("league", "year"),
                    "seasons", List.of("league", "year"));

            assertEquals(Set.of("seasons.matchesLeagueYear", "seasons.matchesReplayOfSeasonId"),
                    compositeFirst.getAllReverseFks().keySet());
            assertEquals(compositeFirst.getAllReverseFks(), singleFirst.getAllReverseFks());
        }
    }

    @Nested
    @DisplayName("A copy keeps the source's names")
    class Copied {

        @Test
        @DisplayName("keeping one of three keys keeps its suffixed name and its alias")
        void keepingOneOfSeveralKeys_keepsItsNameAndAlias() {
            info.addColumn("teams", "id", "bigint");
            info.addForeignKey("matches", "home_team_id", "teams", "id");
            info.addForeignKey("matches", "away_team_id", "teams", "id");
            info.addForeignKey("matches", "winner_team_id", "teams", "id");
            SchemaInfo copy = new SchemaInfo();
            copy.addColumn("teams", "id", "bigint");

            copy.copyRelationships(info, (fromTable, fk) -> fk.fkColumns().contains("home_team_id"));

            assertEquals(Set.of("teams.matchesHomeTeamId"), copy.getAllReverseFks().keySet());
            assertEquals(Set.of("matches.homeTeamId"), copy.getAllForwardFks().keySet());
            assertEquals("matchesByHomeTeam", copy.getRelationshipAlias("teams", "matchesHomeTeamId"));
            assertNotNull(copy.getReverseFk("teams", "matchesByHomeTeam"));
            assertNull(copy.getReverseFk("teams", "matchesByAwayTeam"));
        }
    }

    @Nested
    @DisplayName("Self-referential FK")
    class SelfRefFk {

        @Test
        @DisplayName("self-ref FK: employee.manager_id → employee — reverse uses table name")
        void selfRefFk_singleColumn() {
            info.addForeignKey("employee", "manager_id", "employee", "id");

            assertNotNull(info.getForwardFk("employee", "managerId"),
                    "Forward self-ref FK should be 'managerId'");
            assertNotNull(info.getReverseFk("employee", "employee"),
                    "Reverse self-ref FK should be 'employee' (child table name)");
        }

        @Test
        @DisplayName("two self-ref FKs: manager_id + mentor_id — disambiguated by column suffix")
        void selfRefFk_twoColumns() {
            info.addForeignKey("employee", "manager_id", "employee", "id");
            info.addForeignKey("employee", "mentor_id", "employee", "id");

            assertNotNull(info.getForwardFk("employee", "managerId"));
            assertNotNull(info.getForwardFk("employee", "mentorId"));
            assertNotNull(info.getReverseFk("employee", "employeeManagerId"));
            assertNotNull(info.getReverseFk("employee", "employeeMentorId"));
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
            info.addCompositeForeignKey("child", List.of("parent_id1", "parent_id2"),
                    "parent", List.of("id1", "id2"));

            assertNotNull(info.getForwardFk("child", "parent"),
                    "Composite FK should use table-based name 'parent'");
        }
    }
}
