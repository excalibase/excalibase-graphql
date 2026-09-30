package io.github.excalibase.permissions.compile;

import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.ORGS;
import static io.github.excalibase.permissions.compile.FixtureSchema.U1;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static org.assertj.core.api.Assertions.assertThat;

/** Probes for what memory cannot decide: of a live row by its key, or of an image by its own values. */
class RowProbeTest {

    private static final SchemaInfo SCHEMA = FixtureSchema.schema();
    private static final SessionBinding BINDING = new SessionBinding(Map.of("x-excalibase-user-id", U1));

    @Test
    void columnsRead_areTheTablesOwnColumnsAndItsJoinColumns_notTheRelatedTables() {
        String filter = "{\"_or\": [{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}},"
                + " {\"_not\": {\"title\": {\"_is_null\": true}}},"
                + " {\"publicMembers\": {\"role\": {\"_eq\": \"admin\"}}},"
                + " {\"_exists\": {\"_table\": \"public.orgs\", \"_where\": {\"name\": {\"_eq\": \"acme\"}}}}]}";

        assertThat(RowProbe.columnsRead(parse(filter), NOTES, SCHEMA))
                .containsExactlyInAnyOrder("owner_id", "title", "org_id");
    }

    @Test
    void imageProbe_judgesTheImageAsATypedValuesRow() {
        String filter = "{\"_and\": [{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}},"
                + " {\"publicOrgId\": {\"name\": {\"_eq\": \"acme\"}}}]}";
        Map<String, Object> image = Map.of("id", 7, "owner_id", U1, "org_id", "1", "title", "t");

        SqlFragment probe = RowProbe.ofImage(parse(filter), NOTES, image, SCHEMA, BINDING, new ParamNamer("probe"))
                .orElseThrow();

        assertThat(probe.sql()).isEqualTo("SELECT EXISTS (SELECT 1 FROM (VALUES (CAST(:probe_p0 AS uuid),"
                + " CAST(:probe_p1 AS integer))) AS probe_a0(\"owner_id\", \"org_id\") WHERE"
                + " (probe_a0.\"owner_id\" = :probe_p2 AND EXISTS (SELECT 1 FROM \"public\".\"orgs\" probe_a1"
                + " WHERE probe_a1.\"id\" = probe_a0.\"org_id\" AND probe_a1.\"name\" = :probe_p3)))");
        assertThat(probe.params()).containsEntry("probe_p1", 1L).containsEntry("probe_p3", "acme");
    }

    @Test
    void imageProbe_bindsANullColumnAsNull() {
        Map<String, Object> image = new HashMap<>();
        image.put("owner_id", null);

        SqlFragment probe = RowProbe.ofImage(parse("{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}"), NOTES,
                image, SCHEMA, BINDING, new ParamNamer("probe")).orElseThrow();

        assertThat(probe.params()).containsEntry("probe_p0", null);
    }

    @Test
    void imageProbe_ofAFilterReadingNoColumnOfTheTable_needsNoValuesRow() {
        SqlFragment probe = RowProbe.ofImage(parse("{\"_exists\": {\"_table\": \"public.orgs\", \"_where\": {}}}"),
                NOTES, Map.of(), SCHEMA, BINDING, new ParamNamer("probe")).orElseThrow();

        assertThat(probe.sql()).isEqualTo(
                "SELECT EXISTS (SELECT 1 WHERE EXISTS (SELECT 1 FROM \"public\".\"orgs\" probe_a1 WHERE TRUE))");
    }

    @Test
    void imageProbe_isImpossibleWhenAColumnIsMissingOrNotAValueOfItsType() {
        String filter = "{\"publicOrgId\": {\"name\": {\"_eq\": \"acme\"}}}";

        assertThat(RowProbe.ofImage(parse(filter), NOTES, Map.of("id", 7), SCHEMA, BINDING,
                new ParamNamer("probe"))).isEmpty();
        assertThat(RowProbe.ofImage(parse(filter), NOTES, Map.of("org_id", "not a number"), SCHEMA, BINDING,
                new ParamNamer("probe"))).isEmpty();
        assertThat(RowProbe.columnsRead(parse("{}"), ORGS, SCHEMA)).isEmpty();
    }
}
