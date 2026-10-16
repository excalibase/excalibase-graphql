package io.github.excalibase.access;

import io.github.excalibase.permissions.compile.MysqlFixtureSchema;
import io.github.excalibase.schema.ExposedFunction;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor.Contribution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.excalibase.access.AccessFixture.U1;
import static io.github.excalibase.access.AccessFixture.callable;
import static io.github.excalibase.access.AccessFixture.document;
import static io.github.excalibase.access.AccessFixture.entry;
import static io.github.excalibase.access.AccessFixture.tracked;
import static io.github.excalibase.access.AccessFixture.user;
import static io.github.excalibase.permissions.compile.PermissionDialect.MYSQL;
import static org.assertj.core.api.Assertions.assertThat;

/** The guards a MySQL project's request gets: its quoting, its write-safe filters, its row key. */
class MysqlRequestGuardTest {

    private static final String NOTES = "public.notes";
    private static final String MEMBERS = "public.members";
    private static final String OWNED = "{\"owner_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}";
    private static final String OWN_ORG = "{\"publicOrgId\":{\"publicMembers\":{\"user_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}}}";
    private static final SchemaInfo REFLECTED = MysqlFixtureSchema.schema();

    private static RequestGuard guard(String... entries) {
        return AccessPlan.forRole(MYSQL, REFLECTED, user(entries)).guard(Map.of("x-excalibase-user-id", U1));
    }

    @Test
    void filters_quoteWithBackticks() {
        Contribution select = guard(entry(NOTES, "\"select\":{\"filter\":" + OWNED + ",\"columns\":\"*\"}"))
                .contribute(NOTES, RlsOp.SELECT);

        assertThat(select.sql()).isEqualTo("`notes`.`owner_id` = :perm_p0");
        assertThat(select.params()).containsEntry("perm_p0", U1);
    }

    @Test
    void aDeleteFilterReadingItsOwnTable_readsItThroughADerivedTable() {
        String ownTable = "{\"_exists\":{\"_table\":\"public.notes\","
                + "\"_where\":{\"owner_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}}}";
        RequestGuard guard = guard(entry(NOTES, "\"select\":{\"filter\":{},\"columns\":\"*\"},"
                + "\"delete\":{\"filter\":" + ownTable + "}"));

        assertThat(guard.contribute(NOTES, "n", RlsOp.DELETE).sql()).contains("FROM (SELECT * FROM `public`.`notes`)");
        assertThat(guard.contribute(NOTES, "n", RlsOp.SELECT)).isNull();
    }

    @Test
    void aRelationshipFilter_quotesEveryHop() {
        Contribution contribution = guard(entry(NOTES, "\"select\":{\"filter\":" + OWN_ORG + ",\"columns\":\"*\"}"))
                .contribute(NOTES, "n", RlsOp.SELECT);

        assertThat(contribution.sql()).contains("`public`.`orgs`").contains("`public`.`members`").doesNotContain("\"");
    }

    @Test
    void theRowKey_isTheDatabasesWholePrimaryKey_evenWhenTheRoleCannotReadIt() {
        RequestGuard guard = guard(entry(NOTES, "\"insert\":{\"check\":{},\"columns\":[\"title\"],\"set\":{}}"),
                entry(MEMBERS, "\"select\":{\"filter\":{},\"columns\":[\"role\"]}"));

        assertThat(guard.rowKey(NOTES)).containsExactly("id");
        assertThat(guard.rowKey(MEMBERS)).containsExactly("org_id", "user_id");
        assertThat(guard.rowKey("public.nowhere")).isEmpty();
    }

    @Test
    void trackedFunctions_areNeverCallable() {
        AccessPlan plan = AccessPlan.forRole(MYSQL, REFLECTED, document(
                List.of(entry(NOTES, "\"select\":{\"filter\":{},\"columns\":\"*\"}")),
                List.of(tracked("public.search_notes", "QUERY", true, null)),
                List.of(callable("public.search_notes"))).forRole("user"));

        assertThat(plan.functions().all().stream().map(ExposedFunction::function).toList()).isEmpty();
        assertThat(AccessPlan.allAccess(MYSQL, REFLECTED, document(List.of(),
                List.of(tracked("public.search_notes", "QUERY", false, null)), List.of()).functions())
                .functions().all()).isEmpty();
    }
}
