package io.github.excalibase.schema;

import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TableExposureTest {

    @Test
    void permits_whenUnrestricted_allowsEveryOperationOnEveryTable() {
        TableExposure exposure = TableExposure.UNRESTRICTED;

        assertThat(exposure.permits("public.anything", RlsOp.SELECT)).isTrue();
        assertThat(exposure.permits("public.anything", RlsOp.DELETE)).isTrue();
        assertThat(exposure.exposes("public.anything")).isTrue();
    }

    @Test
    void permits_whenEnforcedAndOperationGranted_returnsTrue() {
        TableExposure exposure = TableExposure.enforcing(
                Map.of("public.orders", Set.of(RlsOp.SELECT, RlsOp.INSERT)));

        assertThat(exposure.permits("public.orders", RlsOp.SELECT)).isTrue();
        assertThat(exposure.permits("public.orders", RlsOp.INSERT)).isTrue();
    }

    @Test
    void permits_whenEnforcedAndOperationNotGranted_returnsFalse() {
        TableExposure exposure = TableExposure.enforcing(
                Map.of("public.orders", Set.of(RlsOp.SELECT)));

        assertThat(exposure.permits("public.orders", RlsOp.UPDATE)).isFalse();
        assertThat(exposure.permits("public.orders", RlsOp.DELETE)).isFalse();
    }

    @Test
    void permits_whenEnforcedAndTableUnknown_returnsFalse() {
        TableExposure exposure = TableExposure.enforcing(
                Map.of("public.orders", Set.of(RlsOp.SELECT)));

        assertThat(exposure.permits("public.secrets", RlsOp.SELECT)).isFalse();
        assertThat(exposure.exposes("public.secrets")).isFalse();
    }

    @Test
    void permits_whenEnforcedWithNoEntries_deniesEverything() {
        TableExposure exposure = TableExposure.enforcing(Map.of());

        assertThat(exposure.permits("public.orders", RlsOp.SELECT)).isFalse();
        assertThat(exposure.enforced()).isTrue();
    }

    @Test
    void enforcing_whenSourceMapMutatedAfterwards_doesNotAffectExposure() {
        Map<String, Set<RlsOp>> mutable = new HashMap<>();
        mutable.put("public.orders", new HashSet<>(Set.of(RlsOp.SELECT)));

        TableExposure exposure = TableExposure.enforcing(mutable);
        mutable.get("public.orders").add(RlsOp.DELETE);
        mutable.put("public.secrets", new HashSet<>(Set.of(RlsOp.SELECT)));

        assertThat(exposure.permits("public.orders", RlsOp.DELETE)).isFalse();
        assertThat(exposure.permits("public.secrets", RlsOp.SELECT)).isFalse();
    }
}
