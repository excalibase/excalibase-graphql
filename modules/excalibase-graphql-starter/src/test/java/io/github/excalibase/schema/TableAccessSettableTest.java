package io.github.excalibase.schema;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableAccessSettableTest {

    private static final String TABLE = "public.orders";

    private SchemaInfo view;
    private TableAccess access;

    @BeforeEach
    void setUp() {
        view = new SchemaInfo();
        view.addColumn(TABLE, "id", "integer");
        view.addColumn(TABLE, "note", "text");
        view.addColumn(TABLE, "owner_id", "text");
        view.setTableSchema(TABLE, "public");
        access = TableAccess.enforcing(Map.of(TABLE, new TableAccess.Rights(
                Set.of(RlsOp.SELECT, RlsOp.INSERT, RlsOp.UPDATE), Set.of("note"), Set.of("note"),
                Set.of("owner_id"), Set.of(), null, false)));
    }

    private static DataError refusal(Runnable write) {
        try {
            write.run();
        } catch (DataErrorException refused) {
            return refused.error();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void aSettableColumn_passes() {
        assertThatCode(() -> access.requireSettable(TABLE, RlsOp.INSERT, List.of("note"), view))
                .doesNotThrowAnyException();
    }

    @Test
    void aColumnTheRoleCanSeeButNotWrite_isPermissionDenied() {
        DataError error = refusal(() -> access.requireSettable(TABLE, RlsOp.INSERT, List.of("id"), view));

        assertThat(error.status()).isEqualTo(403);
        assertThat(error.code()).isEqualTo("permission_denied");
        assertThat(error.column()).isEqualTo("id");
        assertThat(error.message()).contains("'id'").contains("insert");
    }

    @Test
    void aPresetColumn_saysTheServerSetsIt() {
        DataError error = refusal(() -> access.requireSettable(TABLE, RlsOp.INSERT, List.of("owner_id"), view));

        assertThat(error.status()).isEqualTo(403);
        assertThat(error.code()).isEqualTo("permission_denied");
        assertThat(error.message()).contains("'owner_id'").contains("set by the server");
    }

    @Test
    void aColumnOutsideTheRolesView_staysUnknown() {
        assertThatThrownBy(() -> access.requireSettable(TABLE, RlsOp.UPDATE, List.of("secret"), view))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown column 'secret' in update of public.orders");
    }

    @Test
    void withoutPermissions_onlyUnknownColumnsAreRefused() {
        assertThatCode(() -> TableAccess.UNRESTRICTED.requireSettable(TABLE, RlsOp.UPDATE, List.of("id"), view))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TableAccess.UNRESTRICTED.requireSettable(TABLE, RlsOp.UPDATE, List.of("x"), view))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theShortRightsConstructor_hasNoPresets() {
        TableAccess.Rights rights = new TableAccess.Rights(Set.of(RlsOp.INSERT), Set.of("a"), Set.of(), null, false);

        assertThat(rights.insertPresets()).isEmpty();
        assertThat(rights.updatePresets()).isEmpty();
    }
}
