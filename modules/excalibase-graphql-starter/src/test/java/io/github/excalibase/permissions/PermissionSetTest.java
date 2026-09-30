package io.github.excalibase.permissions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Per-role lookups and the immutability of the model. */
class PermissionSetTest {

    private static final SelectPermission READ_ALL = new SelectPermission(BoolExp.TRUE, ColumnSet.ALL, null, false);
    private static final TrackedFunction SEARCH = new TrackedFunction(
            "public.search_orders", TrackedFunction.ExposedAs.QUERY, true, null);
    private static final TrackedFunction REFUND = new TrackedFunction(
            "public.refund", TrackedFunction.ExposedAs.MUTATION, false, "session");

    private static TablePermissions readable(String table, String role) {
        return new TablePermissions(table, role, Optional.of(READ_ALL), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static PermissionSet permissions() {
        return new PermissionSet("proj", 3,
                List.of(readable("public.orders", "user"), readable("public.items", "user"),
                        readable("public.orders", "editor")),
                List.of(SEARCH, REFUND),
                List.of(new FunctionPermission("public.refund", "editor"),
                        new FunctionPermission("public.search_orders", "user")));
    }

    @Test
    void forRole_keysTheRolesTablesByName() {
        RolePermissions user = permissions().forRole("user");

        assertThat(user.role()).isEqualTo("user");
        assertThat(user.tables()).containsOnlyKeys("public.orders", "public.items");
        assertThat(user.table("public.orders")).contains(readable("public.orders", "user"));
        assertThat(user.table("public.missing")).isEmpty();
    }

    @Test
    void forRole_withNoPermissions_permitsNothing() {
        RolePermissions anon = permissions().forRole("anon");

        assertThat(anon.tables()).isEmpty();
        assertThat(anon.explicitlyPermittedFunctions()).isEmpty();
        assertThat(anon.trackedFunctions()).containsExactly(SEARCH, REFUND);
    }

    @Test
    void explicitlyPermitted_listsOnlyThatRolesFunctionPermissions() {
        assertThat(permissions().explicitlyPermitted("editor")).containsExactly("public.refund");
        assertThat(permissions().forRole("user").explicitlyPermittedFunctions())
                .containsExactly("public.search_orders");
    }

    @Test
    void forRole_refusesTheServiceRole_whoseBypassIsDecidedElsewhere() {
        assertThatThrownBy(() -> permissions().forRole("service")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> permissions().forRole("Not A Role")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trackedFunction_lookupByName() {
        assertThat(permissions().forRole("user").trackedFunction("public.refund")).contains(REFUND);
        assertThat(permissions().forRole("user").trackedFunction("public.nope")).isEmpty();
    }

    @Test
    void collections_areCopiedSoTheSourceCannotChangeThem() {
        List<TablePermissions> tables = new ArrayList<>(List.of(readable("public.orders", "user")));
        PermissionSet permissions = new PermissionSet("proj", 1, tables, List.of(), List.of());

        tables.clear();

        assertThat(permissions.tables()).hasSize(1);
        assertThatThrownBy(() -> permissions.tables().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> permissions.forRole("user").tables().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void columnSet_allPermitsEveryColumn_listPermitsOnlyItsOwn() {
        assertThat(ColumnSet.ALL.permits("anything")).isTrue();
        assertThat(ColumnSet.ALL.all()).isTrue();
        ColumnSet some = ColumnSet.of(List.of("id"));
        assertThat(some.permits("id")).isTrue();
        assertThat(some.permits("secret")).isFalse();
        assertThatThrownBy(() -> new ColumnSet(true, List.of("id"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void literal_copiesListsAndMapsImmutably() {
        List<Object> values = new ArrayList<>(List.of(1L, 2L));
        Literal literal = new Literal(values);

        values.add(3L);

        assertThat(literal.value()).isEqualTo(List.of(1L, 2L));
        assertThat(new Literal(Map.of("k", "v")).value()).isEqualTo(Map.of("k", "v"));
    }

    @Test
    void sessionVariable_refusesANameOutsideTheGrammar() {
        assertThatThrownBy(() -> new SessionVariable("user-id")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SessionVariable("X-Excalibase-User-Id").name()).isEqualTo("x-excalibase-user-id");
    }

    @Test
    void selectPermission_refusesANonPositiveLimit() {
        assertThatThrownBy(() -> new SelectPermission(BoolExp.TRUE, ColumnSet.ALL, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permissions_refuseMissingExpressions() {
        assertThatThrownBy(() -> new DeletePermission(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new UpdatePermission(BoolExp.TRUE, null, ColumnSet.ALL, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
