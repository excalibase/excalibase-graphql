package io.github.excalibase.mongodb;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.MaskMode;
import io.github.excalibase.rls.MaskingPlan;
import io.github.excalibase.rls.Operation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.github.excalibase.mongodb.Fixtures.caller;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnSecurityTest {

    private static ColumnPolicy mask(MaskMode mode, String... columns) {
        return new ColumnPolicy("c", "c", "orders", Set.of(columns), Operation.ALL, mode, null, null, 0, true,
                List.of(Assignment.all()));
    }

    @Test
    void hideAndNullAreCarriedFromTheRlsPlan() {
        ColumnSecurity columns = new ColumnSecurity(Fixtures.catalog(),
                List.of(mask(MaskMode.HIDE, "secret"), mask(MaskMode.NULL, "owner")));

        MaskingPlan plan = columns.plan("orders", caller("u1"));

        assertThat(plan.hidden()).containsExactly("secret");
        assertThat(plan.masked()).containsEntry("owner", MaskMode.NULL);
    }

    @Test
    void aColumnThatIsNotDeclaredIsRefusedBecauseATypoWouldLeakIt() {
        List<ColumnPolicy> typo = List.of(mask(MaskMode.HIDE, "secrt"));
        assertThatThrownBy(() -> new ColumnSecurity(Fixtures.catalog(), typo))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("secrt");
    }

    @Test
    void maskModesThatCannotBeRenderedAreRefusedAtStartup() {
        ColumnPolicy hash = mask(MaskMode.HASH, "secret");
        List<ColumnPolicy> hashed = List.of(hash);
        assertThatThrownBy(() -> new ColumnSecurity(Fixtures.catalog(), hashed))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HASH");
    }

    @Test
    void theIdCannotBeMasked() {
        List<ColumnPolicy> maskedId = List.of(mask(MaskMode.HIDE, "id"));
        assertThatThrownBy(() -> new ColumnSecurity(Fixtures.catalog(), maskedId))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("id");
    }

    @Test
    void aPolicyOfNoDeclaredCollectionIsRefusedBecauseItWouldNeverApply() {
        ColumnPolicy other = new ColumnPolicy("c", "c", "elsewhere", Set.of("whatever"), Operation.ALL, MaskMode.HIDE,
                null, null, 0, true, List.of(Assignment.all()));

        List<ColumnPolicy> elsewhere = List.of(other);
        assertThatThrownBy(() -> new ColumnSecurity(Fixtures.catalog(), elsewhere))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("elsewhere");
    }
}
