package io.github.excalibase.access;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.access.AccessFixture.AUDIT;
import static io.github.excalibase.access.AccessFixture.ORDERS;
import static io.github.excalibase.access.AccessFixture.U1;
import static io.github.excalibase.access.AccessFixture.entry;
import static io.github.excalibase.access.AccessFixture.user;
import static org.assertj.core.api.Assertions.assertThat;

/** Realtime delivery: which images reach the subscriber, and with which columns. */
class ChangeFilterTest {

    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private static final Map<String, String> SESSION = Map.of("x-excalibase-user-id", U1);
    private static final ProbeRunner NO_PROBE = (sql, params) -> {
        throw new AssertionError("no probe expected");
    };

    private static ChangeFilter filter(String select) {
        return AccessPlan.forRole(AccessFixture.schema(), user(entry(ORDERS, "\"select\":" + select)))
                .changes(ORDERS, SESSION).orElseThrow();
    }

    private static ChangeFilter owned() {
        return filter("{\"filter\":{\"owner_id\":{\"_eq\":\"X-Excalibase-User-Id\"}},\"columns\":[\"id\",\"owner_id\"]}");
    }

    @Test
    void aMatchingRow_isDeliveredWithOnlyTheSelectColumns() {
        Map<String, Object> row = Map.of("id", 1, "owner_id", U1, "secret", "s");

        assertThat(owned().render("INSERT", row, NO_PROBE)).contains(Map.of("id", 1, "owner_id", U1));
    }

    @Test
    void aRowThatFailsTheFilter_isNotDelivered() {
        assertThat(owned().render("DELETE", Map.of("id", 1, "owner_id", OTHER), NO_PROBE)).isEmpty();
    }

    @Test
    void updateImages_areJudgedSeparately() {
        Map<String, Object> change = Map.of("old", Map.of("id", 1, "owner_id", U1),
                "new", Map.of("id", 1, "owner_id", OTHER));

        assertThat(owned().render("UPDATE", change, NO_PROBE)).contains(Map.of("old", Map.of("id", 1, "owner_id", U1)));
    }

    @Test
    void aRelationshipFilter_probesTheDatabaseForARowThatExists() {
        ChangeFilter filter = filter("{\"filter\":{\"publicCustomerId\":{\"name\":{\"_eq\":\"acme\"}}},\"columns\":\"*\"}");
        List<String> probes = new ArrayList<>();
        ProbeRunner yes = (sql, params) -> probes.add(sql);

        assertThat(filter.render("INSERT", Map.of("id", 7, "customer_id", 3), yes)).isPresent();
        assertThat(probes).singleElement().asString()
                .startsWith("SELECT EXISTS (SELECT 1 FROM \"public\".\"orders\" probe_a0 WHERE probe_a0.\"id\" = :probe_p0");
    }

    @Test
    void anImageNeedingTheDatabase_isWithheldForADeletedRowOrAFailingProbe() {
        ChangeFilter filter = filter("{\"filter\":{\"publicCustomerId\":{\"name\":{\"_eq\":\"acme\"}}},\"columns\":\"*\"}");
        ProbeRunner failing = (sql, params) -> {
            throw new IllegalStateException("database down");
        };

        assertThat(filter.render("DELETE", Map.of("id", 7, "customer_id", 3), NO_PROBE)).isEmpty();
        assertThat(filter.render("INSERT", Map.of("id", 7, "customer_id", 3), failing)).isEmpty();
        assertThat(filter.render("INSERT", Map.of("customer_id", 3), NO_PROBE)).isEmpty();
    }

    @Test
    void aTableTheRoleCannotSelect_hasNoFilter() {
        assertThat(AccessPlan.forRole(AccessFixture.schema(), user()).changes(AUDIT, SESSION)).isEmpty();
    }

    @Test
    void allAccess_passesEveryChangeWhole() {
        ChangeFilter filter = AccessPlan.allAccess(AccessFixture.schema()).changes(ORDERS, Map.of()).orElseThrow();
        Map<String, Object> row = Map.of("id", 1, "secret", "s");

        assertThat(filter.render("INSERT", row, NO_PROBE)).contains(row);
    }
}
