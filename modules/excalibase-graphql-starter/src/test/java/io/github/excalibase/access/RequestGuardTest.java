package io.github.excalibase.access;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor.Contribution;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.github.excalibase.access.AccessFixture.AUDIT;
import static io.github.excalibase.access.AccessFixture.ORDERS;
import static io.github.excalibase.access.AccessFixture.U1;
import static io.github.excalibase.access.AccessFixture.entry;
import static io.github.excalibase.access.AccessFixture.selectAll;
import static io.github.excalibase.access.AccessFixture.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SQL guards one request gets from the plan. */
class RequestGuardTest {

    private static final String OWNED = "{\"owner_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}";
    private static final Map<String, String> SESSION = Map.of("x-excalibase-user-id", U1);

    private static RequestGuard guard(String... entries) {
        return AccessPlan.forRole(AccessFixture.schema(), user(entries)).guard(SESSION);
    }

    @Test
    void select_addsTheSelectFilterBoundToTheSession() {
        Contribution contribution = guard(entry(ORDERS,
                "\"select\":{\"filter\":" + OWNED + ",\"columns\":\"*\"}")).contribute(ORDERS, "o", RlsOp.SELECT);

        assertThat(contribution.sql()).isEqualTo("o.\"owner_id\" = :perm_p0");
        assertThat(contribution.params()).containsEntry("perm_p0", UUID.fromString(U1));
    }

    @Test
    void anEmptyFilter_addsNothing() {
        assertThat(guard(entry(ORDERS, selectAll())).contribute(ORDERS, "o", RlsOp.SELECT)).isNull();
    }

    @Test
    void updateAndDelete_alsoRequireTheSelectFilter() {
        RequestGuard guard = guard(entry(ORDERS, "\"select\":{\"filter\":" + OWNED + ",\"columns\":\"*\"}"
                + ",\"update\":{\"filter\":{\"status\":{\"_eq\":\"open\"}},\"check\":{},\"columns\":\"*\",\"set\":{}}"
                + ",\"delete\":{\"filter\":{}}"));

        assertThat(guard.contribute(ORDERS, "o", RlsOp.UPDATE).sql())
                .isEqualTo("(o.\"status\" = :perm_p0 AND o.\"owner_id\" = :perm_p1)");
        assertThat(guard.contribute(ORDERS, "o", RlsOp.DELETE).sql()).isEqualTo("o.\"owner_id\" = :perm_p2");
    }

    @Test
    void anOperationOrTableThePlanDoesNotHold_isDenied() {
        RequestGuard guard = guard(entry(ORDERS, selectAll()));

        assertThat(guard.contribute(ORDERS, "o", RlsOp.DELETE).sql()).isEqualTo("FALSE");
        assertThat(guard.contribute(AUDIT, "a", RlsOp.SELECT).sql()).isEqualTo("FALSE");
        assertThat(guard.check(ORDERS, "o", RlsOp.INSERT).sql()).isEqualTo("FALSE");
    }

    @Test
    void anUnaliasedStatement_namesTheTableByItsBareName() {
        Contribution contribution = guard(entry(ORDERS,
                "\"select\":{\"filter\":" + OWNED + ",\"columns\":\"*\"}")).contribute(ORDERS, RlsOp.SELECT);

        assertThat(contribution.sql()).isEqualTo("\"orders\".\"owner_id\" = :perm_p0");
    }

    @Test
    void presets_areBoundFromTheSession() {
        RequestGuard guard = guard(entry(ORDERS, selectAll()
                + ",\"insert\":{\"check\":{},\"columns\":[\"total\"],"
                + "\"set\":{\"owner_id\":\"X-Excalibase-User-Id\",\"status\":\"new\"}}"));

        assertThat(guard.presets(ORDERS, RlsOp.INSERT))
                .containsEntry("owner_id", UUID.fromString(U1))
                .containsEntry("status", "new");
        assertThat(guard.presets(ORDERS, RlsOp.UPDATE)).isEmpty();
    }

    @Test
    void check_compilesOverTheWrittenRows() {
        RequestGuard guard = guard(entry(ORDERS, selectAll()
                + ",\"insert\":{\"check\":" + OWNED + ",\"columns\":\"*\",\"set\":{}}"));

        assertThat(guard.check(ORDERS, "w", RlsOp.INSERT).sql()).isEqualTo("w.\"owner_id\" = :perm_p0");
    }

    @Test
    void aMissingSessionVariable_failsTheRequest() {
        RequestGuard guard = AccessPlan.forRole(AccessFixture.schema(), user(entry(ORDERS,
                "\"select\":{\"filter\":" + OWNED + ",\"columns\":\"*\"}"))).guard(Map.of());

        assertThatThrownBy(() -> guard.contribute(ORDERS, "o", RlsOp.SELECT))
                .isInstanceOfSatisfying(PermissionEvaluationException.class, e ->
                        assertThat(e.code()).isEqualTo(PermissionEvaluationException.MISSING_SESSION_VARIABLE));
    }

    @Test
    void insertWithoutSelect_deniesEveryRead_whileItsCheckAndPresetsApply() {
        RequestGuard guard = guard(entry(AUDIT,
                "\"insert\":{\"check\":{\"note\":{\"_neq\":\"\"}},\"columns\":[\"note\"],\"set\":{\"id\":7}}"));

        assertThat(guard.contribute(AUDIT, "a", RlsOp.SELECT).sql()).isEqualTo("FALSE");
        assertThat(guard.contribute(AUDIT, "a", RlsOp.UPDATE).sql()).isEqualTo("FALSE");
        assertThat(guard.check(AUDIT, "w", RlsOp.INSERT).sql()).isEqualTo("w.\"note\" <> :perm_p0");
        assertThat(((Number) guard.presets(AUDIT, RlsOp.INSERT).get("id")).intValue()).isEqualTo(7);
    }

    @Test
    void allAccess_addsNoGuard() {
        RequestGuard guard = AccessPlan.allAccess(AccessFixture.schema()).guard(Map.of());

        assertThat(guard.contribute(ORDERS, "o", RlsOp.DELETE)).isNull();
        assertThat(guard.check(ORDERS, "o", RlsOp.INSERT)).isNull();
        assertThat(guard.presets(ORDERS, RlsOp.INSERT)).isEmpty();
    }
}
