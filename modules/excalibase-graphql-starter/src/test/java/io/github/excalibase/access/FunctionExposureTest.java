package io.github.excalibase.access;

import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.ExposedFunction;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.excalibase.access.AccessFixture.AUDIT;
import static io.github.excalibase.access.AccessFixture.ORDERS;
import static io.github.excalibase.access.AccessFixture.ORDER_VIEW;
import static io.github.excalibase.access.AccessFixture.callable;
import static io.github.excalibase.access.AccessFixture.document;
import static io.github.excalibase.access.AccessFixture.entry;
import static io.github.excalibase.access.AccessFixture.selectAll;
import static io.github.excalibase.access.AccessFixture.tracked;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which tracked functions a role may call (docs/features/permissions.md §6): tracked, still valid
 * against the database, the return table selectable, and permitted explicitly or by inference.
 */
class FunctionExposureTest {

    private static final SchemaInfo REFLECTED = AccessFixture.schema();

    private static final String SEARCH = "public.search_orders";
    private static final String PLACE = "public.place_order";

    private static AccessPlan userPlan(List<String> tables, List<String> functions, List<String> permissions) {
        return AccessPlan.forRole(REFLECTED, document(tables, functions, permissions).forRole("user"));
    }

    private static List<String> callableBy(AccessPlan plan) {
        return plan.functions().all().stream().map(ExposedFunction::function).toList();
    }

    private static AccessPlan withOrders(String... functions) {
        return userPlan(List.of(entry(ORDERS, selectAll())), List.of(functions), List.of());
    }

    @Test
    void aReturnTableTheRoleMayOnlyInsertInto_makesNoFunctionCallable() {
        AccessPlan plan = userPlan(List.of(entry(ORDERS, "\"insert\":{\"check\":{},\"columns\":\"*\",\"set\":{}}")),
                List.of(tracked(SEARCH, "QUERY", true, null), tracked(PLACE, "MUTATION", false, "session")),
                List.of(callable(SEARCH), callable(PLACE)));

        assertThat(callableBy(plan)).isEmpty();
    }

    @Test
    void anUntrackedFunction_isNeverCallable() {
        assertThat(callableBy(withOrders())).isEmpty();
    }

    @Test
    void aStableQuery_isInferredFromTheReturnTablesSelect() {
        AccessPlan plan = withOrders(tracked(SEARCH, "QUERY", true, null));

        assertThat(callableBy(plan)).containsExactly(SEARCH);
        ExposedFunction search = plan.functions().named(SEARCH).orElseThrow();
        assertThat(search.operation()).isEqualTo(ExposedFunction.Operation.QUERY);
        assertThat(search.fieldName()).isEqualTo("publicSearchOrders");
        assertThat(search.returnTable()).isEqualTo(ORDERS);
        assertThat(search.returnsSet()).isTrue();
        assertThat(search.arguments()).containsExactly(new ExposedFunction.Argument("p_query", "text", true),
                new ExposedFunction.Argument("p_limit", "integer", false));
    }

    @Test
    void withInferenceOff_onlyAnExplicitPermissionGrantsAQuery() {
        String searchNoInference = tracked(SEARCH, "QUERY", false, null);

        assertThat(callableBy(withOrders(searchNoInference))).isEmpty();
        assertThat(callableBy(userPlan(List.of(entry(ORDERS, selectAll())), List.of(searchNoInference),
                List.of(callable(SEARCH))))).containsExactly(SEARCH);
    }

    @Test
    void aVolatileMutation_needsAnExplicitPermission_evenWithInferenceOn() {
        String place = tracked(PLACE, "MUTATION", true, "session");

        assertThat(callableBy(withOrders(place))).isEmpty();
        AccessPlan plan = userPlan(List.of(entry(ORDERS, selectAll())), List.of(place), List.of(callable(PLACE)));
        ExposedFunction placed = plan.functions().named(PLACE).orElseThrow();
        assertThat(placed.operation()).isEqualTo(ExposedFunction.Operation.MUTATION);
        assertThat(placed.arguments()).extracting(ExposedFunction.Argument::name).containsExactly("p_total");
        assertThat(placed.sessionArgument()).isEqualTo(new ExposedFunction.SessionArgument("session", "jsonb"));
    }

    @Test
    void anExplicitPermission_isNotEnough_withoutSelectOnTheReturnTable() {
        AccessPlan plan = userPlan(List.of(), List.of(tracked(SEARCH, "QUERY", true, null)), List.of(callable(SEARCH)));

        assertThat(callableBy(plan)).isEmpty();
    }

    @Test
    void aReturnTableTheRoleCannotSelect_hidesTheFunction() {
        AccessPlan plan = withOrders(tracked("public.audit_rows", "QUERY", true, null));

        assertThat(callableBy(plan)).isEmpty();
        assertThat(callableBy(userPlan(List.of(entry(AUDIT, selectAll())),
                List.of(tracked("public.audit_rows", "QUERY", true, null)), List.of())))
                .containsExactly("public.audit_rows");
    }

    @Test
    void aFunctionReturningAView_isCallableWhenTheViewIsSelectable() {
        AccessPlan plan = userPlan(List.of(entry(ORDER_VIEW, selectAll())),
                List.of(tracked("public.view_rows", "QUERY", true, null)), List.of());

        assertThat(callableBy(plan)).containsExactly("public.view_rows");
    }

    @Test
    void aSingleRowFunction_isExposedAsOneRow() {
        AccessPlan plan = withOrders(tracked("public.latest_order", "QUERY", true, null));

        assertThat(plan.functions().named("public.latest_order").orElseThrow().returnsSet()).isFalse();
    }

    @Test
    void aVolatilityMismatch_isInvalid_neverWidened() {
        assertThat(callableBy(withOrders(tracked(SEARCH, "MUTATION", true, null)))).isEmpty();
        assertThat(callableBy(userPlan(List.of(entry(ORDERS, selectAll())),
                List.of(tracked(PLACE, "QUERY", true, "session")), List.of(callable(PLACE))))).isEmpty();
    }

    @Test
    void invalidFunctions_areNeverCallable() {
        AccessPlan plan = userPlan(List.of(entry(ORDERS, selectAll())), List.of(
                tracked("public.twice", "QUERY", true, null),
                tracked("public.do_thing", "MUTATION", true, null),
                tracked("public.order_count", "QUERY", true, null),
                tracked("public.ghost", "QUERY", true, null),
                tracked("public.orders", "QUERY", true, null),
                tracked("public.bad_where", "QUERY", true, null)),
                List.of(callable("public.do_thing")));

        assertThat(callableBy(plan)).isEmpty();
    }

    @Test
    void aSessionArgumentThatIsNotAJsonArgument_isInvalid() {
        assertThat(callableBy(withOrders(tracked(SEARCH, "QUERY", true, "p_query")))).isEmpty();
        assertThat(callableBy(withOrders(tracked(SEARCH, "QUERY", true, "nope")))).isEmpty();
    }

    @Test
    void service_callsEveryValidTrackedFunction_andNothingElse() {
        PermissionSet permissions = document(List.of(), List.of(
                tracked(SEARCH, "QUERY", false, null),
                tracked(PLACE, "MUTATION", false, "session"),
                tracked("public.twice", "QUERY", true, null)), List.of());

        AccessPlan plan = AccessPlan.allAccess(REFLECTED, permissions.functions());

        assertThat(callableBy(plan)).containsExactlyInAnyOrder(SEARCH, PLACE);
        assertThat(callableBy(AccessPlan.allAccess(REFLECTED))).isEmpty();
    }
}
