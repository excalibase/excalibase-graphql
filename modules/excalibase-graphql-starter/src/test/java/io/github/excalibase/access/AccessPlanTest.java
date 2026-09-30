package io.github.excalibase.access;

import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.Test;

import static io.github.excalibase.access.AccessFixture.AUDIT;
import static io.github.excalibase.access.AccessFixture.CUSTOMERS;
import static io.github.excalibase.access.AccessFixture.ORDERS;
import static io.github.excalibase.access.AccessFixture.ORDER_VIEW;
import static io.github.excalibase.access.AccessFixture.entry;
import static io.github.excalibase.access.AccessFixture.selectAll;
import static io.github.excalibase.access.AccessFixture.user;
import static org.assertj.core.api.Assertions.assertThat;

/** The role's view of the schema and its rights, as built from the reflected schema and its permissions. */
class AccessPlanTest {

    private static final SchemaInfo REFLECTED = AccessFixture.schema();

    private static AccessPlan plan(String... entries) {
        return AccessPlan.forRole(REFLECTED, user(entries));
    }

    @Test
    void aTableWithoutSelect_isNotInTheView() {
        AccessPlan plan = plan(entry(CUSTOMERS, selectAll()),
                entry(AUDIT, "\"delete\":{\"filter\":{}}"));

        assertThat(plan.view().getTableNames()).containsExactly(CUSTOMERS);
        assertThat(plan.access().permits(AUDIT, RlsOp.DELETE)).isFalse();
    }

    @Test
    void noPermissions_meansAnEmptyView() {
        AccessPlan plan = plan();

        assertThat(plan.view().getTableNames()).isEmpty();
        assertThat(plan.functions().all()).isEmpty();
    }

    @Test
    void onlyTheSelectColumnsAreColumnsOfTheTable() {
        AccessPlan plan = plan(entry(ORDERS, "\"select\":{\"filter\":{},\"columns\":[\"id\",\"total\"]}"));

        assertThat(plan.view().getColumns(ORDERS)).containsExactly("id", "total");
        assertThat(plan.view().getColumnType(ORDERS, "secret")).isNull();
    }

    @Test
    void star_isEveryCurrentColumn() {
        AccessPlan plan = plan(entry(ORDERS, selectAll()));

        assertThat(plan.view().getColumns(ORDERS)).isEqualTo(REFLECTED.getColumns(ORDERS));
    }

    @Test
    void aSelectNamingAnUnknownColumn_isDropped_soTheTableDisappears() {
        AccessPlan plan = plan(entry(ORDERS, "\"select\":{\"filter\":{},\"columns\":[\"id\",\"ghost\"]}"));

        assertThat(plan.view().hasTable(ORDERS)).isFalse();
    }

    @Test
    void aFilterNamingAnUnknownColumn_dropsThePermission() {
        AccessPlan plan = plan(entry(ORDERS, "\"select\":{\"filter\":{\"ghost\":{\"_eq\":1}},\"columns\":\"*\"}"));

        assertThat(plan.view().hasTable(ORDERS)).isFalse();
    }

    @Test
    void aPermissionForAnUnknownTable_isIgnored() {
        AccessPlan plan = plan(entry("public.ghost", selectAll()), entry(CUSTOMERS, selectAll()));

        assertThat(plan.view().getTableNames()).containsExactly(CUSTOMERS);
    }

    @Test
    void anInvalidInsert_isDropped_whileTheSelectStands() {
        AccessPlan plan = plan(entry(ORDERS, selectAll()
                + ",\"insert\":{\"check\":{},\"columns\":[\"total\",\"ghost\"],\"set\":{}}"));

        assertThat(plan.view().hasTable(ORDERS)).isTrue();
        assertThat(plan.access().permits(ORDERS, RlsOp.INSERT)).isFalse();
    }

    @Test
    void aPresetOnAnUnknownColumnOrOfTheWrongType_dropsTheInsert() {
        AccessPlan unknown = plan(entry(ORDERS, selectAll()
                + ",\"insert\":{\"check\":{},\"columns\":\"*\",\"set\":{\"ghost\":1}}"));
        AccessPlan mistyped = plan(entry(ORDERS, selectAll()
                + ",\"insert\":{\"check\":{},\"columns\":\"*\",\"set\":{\"customer_id\":\"abc\"}}"));

        assertThat(unknown.access().permits(ORDERS, RlsOp.INSERT)).isFalse();
        assertThat(mistyped.access().permits(ORDERS, RlsOp.INSERT)).isFalse();
    }

    @Test
    void settableColumns_leaveOutPresets() {
        AccessPlan plan = plan(entry(ORDERS, selectAll()
                + ",\"insert\":{\"check\":{},\"columns\":[\"total\",\"owner_id\"],"
                + "\"set\":{\"owner_id\":\"X-Excalibase-User-Id\"}}"
                + ",\"update\":{\"filter\":{},\"check\":{},\"columns\":[\"status\"],\"set\":{}}"));
        TableAccess access = plan.access();

        assertThat(access.settableColumns(ORDERS, RlsOp.INSERT, plan.view())).containsExactly("total");
        assertThat(access.settableColumns(ORDERS, RlsOp.UPDATE, plan.view())).containsExactly("status");
        assertThat(access.permits(ORDERS, RlsOp.DELETE)).isFalse();
    }

    @Test
    void aWriteOnlyColumn_isTypedButNotListed() {
        AccessPlan plan = plan(entry(ORDERS, "\"select\":{\"filter\":{},\"columns\":[\"id\"]}"
                + ",\"insert\":{\"check\":{},\"columns\":[\"secret\"],\"set\":{}}"));

        assertThat(plan.view().getColumns(ORDERS)).containsExactly("id");
        assertThat(plan.view().getColumnType(ORDERS, "secret")).isEqualTo("text");
        assertThat(plan.access().settableColumns(ORDERS, RlsOp.INSERT, plan.view())).containsExactly("secret");
    }

    @Test
    void limitAndAggregations_comeFromTheSelect() {
        AccessPlan plan = plan(
                entry(ORDERS, "\"select\":{\"filter\":{},\"columns\":\"*\",\"limit\":5,\"allowAggregations\":true}"),
                entry(CUSTOMERS, selectAll()));

        assertThat(plan.access().rowLimit(ORDERS)).isEqualTo(5);
        assertThat(plan.access().allowsAggregations(ORDERS)).isTrue();
        assertThat(plan.access().rowLimit(CUSTOMERS)).isNull();
        assertThat(plan.access().allowsAggregations(CUSTOMERS)).isFalse();
    }

    @Test
    void relationships_existOnlyWhenBothEndsAndTheirJoinColumnsAreSelectable() {
        AccessPlan both = plan(entry(ORDERS, selectAll()), entry(CUSTOMERS, selectAll()));
        AccessPlan childOnly = plan(entry(ORDERS, selectAll()));
        AccessPlan hiddenJoinColumn = plan(
                entry(ORDERS, "\"select\":{\"filter\":{},\"columns\":[\"id\",\"total\"]}"),
                entry(CUSTOMERS, selectAll()));

        assertThat(both.view().getAllForwardFks()).hasSize(1);
        assertThat(both.view().getAllReverseFks()).hasSize(1);
        assertThat(childOnly.view().getAllForwardFks()).isEmpty();
        assertThat(hiddenJoinColumn.view().getAllForwardFks()).isEmpty();
        assertThat(hiddenJoinColumn.view().getAllReverseFks()).isEmpty();
    }

    @Test
    void thePrimaryKey_isKeptOnlyWhenSelectable() {
        AccessPlan withKey = plan(entry(ORDERS, selectAll()));
        AccessPlan withoutKey = plan(entry(ORDERS, "\"select\":{\"filter\":{},\"columns\":[\"total\"]}"));

        assertThat(withKey.view().hasPrimaryKey(ORDERS)).isTrue();
        assertThat(withoutKey.view().hasPrimaryKey(ORDERS)).isFalse();
    }

    @Test
    void untrackedFunctionsAndComputedFields_areNotServedToARole() {
        AccessPlan plan = plan(entry(ORDERS, selectAll()));

        assertThat(plan.functions().all()).isEmpty();
        assertThat(plan.view().getComputedFields(ORDERS)).isNull();
    }

    @Test
    void aView_isReadOnlyWhateverThePermissionSays() {
        AccessPlan plan = plan(entry(ORDER_VIEW, selectAll() + ",\"delete\":{\"filter\":{}}"));

        assertThat(plan.view().isView(ORDER_VIEW)).isTrue();
        assertThat(plan.access().permits(ORDER_VIEW, RlsOp.SELECT)).isTrue();
        assertThat(plan.access().permits(ORDER_VIEW, RlsOp.DELETE)).isFalse();
    }

    @Test
    void allAccess_servesEverythingTheDatabaseHas() {
        AccessPlan plan = AccessPlan.allAccess(REFLECTED);

        assertThat(plan.allAccess()).isTrue();
        assertThat(plan.view().getTableNames()).isEqualTo(REFLECTED.getTableNames());
        assertThat(plan.functions().all()).isEmpty();
        assertThat(plan.access().permits(ORDERS, RlsOp.DELETE)).isTrue();
        assertThat(plan.access().allowsAggregations(ORDERS)).isTrue();
        assertThat(plan.access().settableColumns(ORDERS, RlsOp.INSERT, plan.view()))
                .isEqualTo(REFLECTED.getColumns(ORDERS));
    }
}
