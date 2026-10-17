package io.github.excalibase.permissions.compile;

import org.junit.jupiter.api.Test;

import static io.github.excalibase.permissions.compile.FixtureSchema.MEMBERS;
import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.ORGS;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static org.assertj.core.api.Assertions.assertThat;

/** The tables a check reads beyond its row, which a write judged after the fact must not have changed. */
class TablesReadTest {

    @Test
    void columnsOfTheRowItself_readNoTable() {
        assertThat(TablesRead.of(parse("{\"_and\": [{\"title\": {\"_eq\": \"a\"}}, {\"_not\": {\"owner_id\":"
                + " {\"_is_null\": true}}}]}"), NOTES, FixtureSchema.schema())).isEmpty();
    }

    @Test
    void relationshipsAndExists_readTheirTables_atAnyDepth() {
        assertThat(TablesRead.of(parse("{\"_or\": [{\"publicOrgId\": {\"publicMembers\": {\"role\": {\"_eq\": \"admin\"}}}},"
                + " {\"_exists\": {\"_table\": \"public.notes\", \"_where\": {}}}]}"), NOTES, FixtureSchema.schema()))
                .containsExactlyInAnyOrder(ORGS, MEMBERS, NOTES);
    }
}
