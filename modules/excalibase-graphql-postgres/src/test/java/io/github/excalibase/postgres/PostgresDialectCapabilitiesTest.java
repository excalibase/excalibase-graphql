package io.github.excalibase.postgres;

import io.github.excalibase.FilterCapabilities;
import io.github.excalibase.FilterColumnKind;
import io.github.excalibase.SqlDialect.JsonPredicate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresDialectCapabilitiesTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @Test
    void postgresRunsEveryOperatorTheEngineKnows() {
        for (FilterColumnKind kind : FilterColumnKind.values()) {
            assertThat(dialect.filterCapabilities().operators(kind)).as(kind.name())
                    .isEqualTo(FilterCapabilities.known(kind));
        }
        assertThat(dialect.filterCapabilities().restOperators()).isEqualTo(FilterCapabilities.knownRest());
    }

    @Test
    void keySetPredicatesKeepTheirArrayForm() {
        assertThat(dialect.jsonKeysPredicateSql(JsonPredicate.HAS_ALL_KEYS, "t.\"m\"",
                List.of(":k0", ":k1")))
                .contains("jsonb_exists_all(t.\"m\", ARRAY[:k0, :k1])");
    }
}
