package io.github.excalibase.access;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.excalibase.permissions.compile.SessionBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.access.AccessFixture.AUDIT;
import static io.github.excalibase.access.AccessFixture.ORDERS;
import static io.github.excalibase.access.AccessFixture.U1;
import static io.github.excalibase.access.AccessFixture.entry;
import static io.github.excalibase.access.AccessFixture.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realtime delivery: which images reach the subscriber, and with which columns. An image of a row that
 * is gone (a DELETE, an UPDATE's old image) is judged by its own values, so it must carry them.
 */
class ChangeFilterTest {

    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private static final Map<String, String> SESSION = Map.of("x-excalibase-user-id", U1);
    private static final String OWNED_FILTER = "{\"owner_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}";
    private static final String BY_CUSTOMER = "{\"publicCustomerId\":{\"name\":{\"_eq\":\"acme\"}}}";
    private static final ProbeRunner NO_PROBE = (sql, params) -> {
        throw new AssertionError("no probe expected");
    };

    private final Logger warningLog = (Logger) LoggerFactory.getLogger(IncompleteImageWarning.class);
    private final ListAppender<ILoggingEvent> warnings = new ListAppender<>();

    @BeforeEach
    void captureWarnings() {
        warnings.start();
        warningLog.addAppender(warnings);
    }

    @AfterEach
    void releaseWarnings() {
        warningLog.detachAppender(warnings);
    }

    private static ChangeFilter filter(String filter, String columns) {
        AccessPlan plan = AccessPlan.forRole(AccessFixture.schema(), user(entry(ORDERS,
                "\"select\":{\"filter\":" + filter + ",\"columns\":" + columns + "}")));
        return new SelectChangeFilter(ORDERS, plan.rules(ORDERS).orElseThrow().select(), plan.reflected(),
                new SessionBinding(SESSION), new IncompleteImageWarning(() -> Instant.EPOCH));
    }

    private static ChangeFilter owned() {
        return filter(OWNED_FILTER, "[\"id\",\"owner_id\"]");
    }

    private static Map<String, Object> order(int id, String owner) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("customer_id", 3);
        row.put("owner_id", owner);
        row.put("total", "9.50");
        row.put("status", "open");
        row.put("secret", "s");
        return row;
    }

    @Test
    void aMatchingInsert_isDeliveredWithOnlyTheSelectColumns() {
        assertThat(owned().render("INSERT", order(1, U1), NO_PROBE)).contains(Map.of("id", 1, "owner_id", U1));
    }

    @Test
    void aDeleteOfARowTheSubscriberCouldSee_isDeliveredWithOnlyTheSelectColumns() {
        assertThat(owned().render("DELETE", order(1, U1), NO_PROBE)).contains(Map.of("id", 1, "owner_id", U1));
    }

    @Test
    void aDeleteOfARowTheSubscriberCouldNotSee_isNotDelivered() {
        assertThat(owned().render("DELETE", order(1, OTHER), NO_PROBE)).isEmpty();
    }

    @Test
    void aKeyOnlyDelete_isWithheldAndWarnedAbout() {
        assertThat(owned().render("DELETE", Map.of("id", 1), NO_PROBE)).isEmpty();

        assertThat(warnings.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).hasToString("WARN");
            assertThat(event.getFormattedMessage()).startsWith("realtime_image_incomplete table=public.orders");
        });
    }

    @Test
    void aDeleteLackingAColumnTheSubscriberWouldBeSent_isWithheld() {
        ChangeFilter filter = filter(OWNED_FILTER, "[\"id\",\"owner_id\",\"total\"]");

        assertThat(filter.render("DELETE", Map.of("id", 1, "owner_id", U1), NO_PROBE)).isEmpty();
        assertThat(warnings.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .asString().contains("total");
    }

    @Test
    void aDeleteUnderARelationshipFilter_isJudgedByProbingItsImage() {
        ChangeFilter filter = filter(BY_CUSTOMER, "\"*\"");
        List<String> probes = new ArrayList<>();
        ProbeRunner yes = (sql, params) -> probes.add(sql);

        assertThat(filter.render("DELETE", order(7, OTHER), yes)).contains(order(7, OTHER));
        assertThat(filter.render("DELETE", order(7, OTHER), (sql, params) -> false)).isEmpty();
        assertThat(probes).singleElement().asString()
                .startsWith("SELECT EXISTS (SELECT 1 FROM (VALUES (CAST(:probe_p0 AS integer))) AS probe_a0(\"customer_id\")");
    }

    @Test
    void aFailingProbe_withholdsTheImage() {
        ProbeRunner failing = (sql, params) -> {
            throw new IllegalStateException("database down");
        };

        assertThat(filter(BY_CUSTOMER, "\"*\"").render("DELETE", order(7, U1), failing)).isEmpty();
        assertThat(filter(BY_CUSTOMER, "\"*\"").render("INSERT", order(7, U1), failing)).isEmpty();
    }

    @Test
    void anInsertUnderARelationshipFilter_probesTheLiveRowByItsKey() {
        List<String> probes = new ArrayList<>();
        ProbeRunner yes = (sql, params) -> probes.add(sql);

        assertThat(filter(BY_CUSTOMER, "\"*\"").render("INSERT", Map.of("id", 7, "customer_id", 3), yes)).isPresent();
        assertThat(probes).singleElement().asString()
                .startsWith("SELECT EXISTS (SELECT 1 FROM \"public\".\"orders\" probe_a0 WHERE probe_a0.\"id\" = :probe_p0");
    }

    @Test
    void updateImages_areJudgedSeparately() {
        Map<String, Object> change = Map.of("old", order(1, U1), "new", order(1, OTHER));

        assertThat(owned().render("UPDATE", change, NO_PROBE))
                .contains(Map.of("old", Map.of("id", 1, "owner_id", U1)));
    }

    @Test
    void anUpdatesKeyOnlyOldImage_isWithheldWhileItsNewImageIsJudged() {
        Map<String, Object> change = Map.of("old", Map.of("id", 1), "new", order(2, U1));

        assertThat(owned().render("UPDATE", change, NO_PROBE)).contains(Map.of("new", Map.of("id", 2, "owner_id", U1)));
        assertThat(warnings.list).hasSize(1);
    }

    @Test
    void anUpdateUnderARelationshipFilter_probesTheOldImageByValueAndTheNewRowByKey() {
        List<String> probes = new ArrayList<>();
        ProbeRunner yes = (sql, params) -> probes.add(sql);
        Map<String, Object> change = Map.of("old", order(1, U1), "new", order(1, U1));

        assertThat(filter(BY_CUSTOMER, "[\"id\"]").render("UPDATE", change, yes))
                .contains(Map.of("old", Map.of("id", 1), "new", Map.of("id", 1)));
        assertThat(probes).hasSize(2);
        assertThat(probes.get(0)).contains("FROM (VALUES (");
        assertThat(probes.get(1)).contains("FROM \"public\".\"orders\" probe_a0 WHERE probe_a0.\"id\" =");
    }

    @Test
    void aTableTheRoleCannotSelect_hasNoFilter() {
        assertThat(AccessPlan.forRole(AccessFixture.schema(), user()).changes(AUDIT, SESSION)).isEmpty();
    }

    @Test
    void theRolesPlan_judgesChangesWithTheSelectRule() {
        ChangeFilter filter = AccessPlan.forRole(AccessFixture.schema(), user(entry(ORDERS,
                "\"select\":{\"filter\":" + OWNED_FILTER + ",\"columns\":[\"id\"]}"))).changes(ORDERS, SESSION).orElseThrow();

        assertThat(filter.render("DELETE", order(1, U1), NO_PROBE)).contains(Map.of("id", 1));
    }

    @Test
    void allAccess_passesEveryChangeWhole() {
        ChangeFilter filter = AccessPlan.allAccess(AccessFixture.schema()).changes(ORDERS, Map.of()).orElseThrow();
        Map<String, Object> keyOnly = Map.of("id", 1);

        assertThat(filter.render("DELETE", keyOnly, NO_PROBE)).contains(keyOnly);
    }
}
