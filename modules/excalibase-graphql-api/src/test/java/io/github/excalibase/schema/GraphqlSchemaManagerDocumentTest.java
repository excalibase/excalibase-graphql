package io.github.excalibase.schema;

import io.github.excalibase.document.CountingProvider;
import io.github.excalibase.security.Principal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A document database has no SQL schema, and deciding that must cost nothing on the requests of every database. */
class GraphqlSchemaManagerDocumentTest {

    private static GraphqlSchemaManager manager() {
        return new GraphqlSchemaManager(null, 30, CountingProvider.TYPE, GraphqlSchemaManager.DEFAULT_MAX_QUERY_DEPTH, 30,
                "", null, null, null, true, false);
    }

    @Test
    void whetherTheDatabaseIsADocumentDatabaseIsDecidedOnceNotOnEveryRequest() {
        GraphqlSchemaManager manager = manager();
        int scansBefore = CountingProvider.INSTANTIATED.get();

        for (int request = 0; request < 50; request++) {
            assertThat(manager.documentMode()).isTrue();
            assertThat(manager.planFor(new Principal("user", false, null, Map.of()), null)).isNull();
        }

        assertThat(CountingProvider.INSTANTIATED.get()).isEqualTo(scansBefore);
    }

    @Test
    void realtimeIsNotServedForADocumentDatabase() {
        GraphqlSchemaManager manager = manager();
        Principal user = new Principal("user", false, null, Map.of());

        assertThatThrownBy(() -> manager.realtime(null, "p1", user, "orders"))
                .isInstanceOf(SurfaceNotServedException.class);
    }
}
