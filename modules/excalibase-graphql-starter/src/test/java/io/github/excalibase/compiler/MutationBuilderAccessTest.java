package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.spi.MutationCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class MutationBuilderAccessTest {

    private static final String TABLE = "public.orders";

    @Mock SqlDialect dialect;
    @Mock FilterBuilder filterBuilder;
    @Mock QueryBuilder queryBuilder;
    @Mock MutationCompiler mutationCompiler;

    private SchemaInfo schemaInfo;

    @BeforeEach
    void setUp() {
        schemaInfo = new SchemaInfo();
        schemaInfo.addColumn(TABLE, "id", "integer");
        schemaInfo.setTableSchema(TABLE, "public");
    }

    private MutationBuilder builderWith(Set<RlsOp> granted) {
        TableAccess access = TableAccess.enforcing(Map.of(TABLE,
                new TableAccess.Rights(granted, Set.of("id"), Set.of(), null, false)));
        return new MutationBuilder(schemaInfo, dialect, filterBuilder, "public",
                queryBuilder, mutationCompiler, access);
    }

    @Test
    void resolveMutationTable_whenInsertGranted_resolvesTheCreateField() {
        MutationBuilder builder = builderWith(Set.of(RlsOp.SELECT, RlsOp.INSERT));

        assertThat(builder.resolveMutationTable("PublicOrders", "createPublicOrders")).isEqualTo(TABLE);
        assertThat(builder.resolveMutationTable("PublicOrders", "createManyPublicOrders")).isEqualTo(TABLE);
    }

    @Test
    void resolveMutationTable_whenInsertNotGranted_leavesTheCreateFieldUnresolved() {
        MutationBuilder builder = builderWith(Set.of(RlsOp.SELECT));

        assertThat(builder.resolveMutationTable("PublicOrders", "createPublicOrders")).isNull();
        assertThat(builder.resolveMutationTable("PublicOrders", "createManyPublicOrders")).isNull();
    }

    @Test
    void resolveMutationTable_whenUpdateNotGranted_leavesTheUpdateFieldUnresolved() {
        MutationBuilder builder = builderWith(Set.of(RlsOp.SELECT, RlsOp.INSERT));

        assertThat(builder.resolveMutationTable("PublicOrders", "updatePublicOrders")).isNull();
    }

    @Test
    void resolveMutationTable_whenDeleteNotGranted_leavesTheDeleteFieldUnresolved() {
        MutationBuilder builder = builderWith(Set.of(RlsOp.SELECT, RlsOp.UPDATE));

        assertThat(builder.resolveMutationTable("PublicOrders", "deletePublicOrders")).isNull();
    }

    @Test
    void resolveMutationTable_whenAllOperationsGranted_resolvesEveryMutationField() {
        MutationBuilder builder = builderWith(Set.of(RlsOp.SELECT, RlsOp.INSERT, RlsOp.UPDATE, RlsOp.DELETE));

        assertThat(builder.resolveMutationTable("PublicOrders", "updatePublicOrders")).isEqualTo(TABLE);
        assertThat(builder.resolveMutationTable("PublicOrders", "deletePublicOrders")).isEqualTo(TABLE);
    }

    @Test
    void resolveMutationTable_whenAccessUnrestricted_resolvesEveryMutationField() {
        MutationBuilder builder = new MutationBuilder(schemaInfo, dialect, filterBuilder, "public",
                queryBuilder, mutationCompiler, TableAccess.UNRESTRICTED);

        assertThat(builder.resolveMutationTable("PublicOrders", "deletePublicOrders")).isEqualTo(TABLE);
    }

    @Test
    void resolveMutationTable_whenTableUnknown_returnsNull() {
        MutationBuilder builder = builderWith(Set.of(RlsOp.SELECT, RlsOp.INSERT));

        assertThat(builder.resolveMutationTable("Ghost", "createGhost")).isNull();
    }

    @Test
    void permitsUpsert_whenUpdateNotGranted_returnsFalse() {
        MutationBuilder builder = builderWith(Set.of(RlsOp.SELECT, RlsOp.INSERT));

        assertThat(builder.permitsUpsert(TABLE)).isFalse();
    }

    @Test
    void permitsUpsert_whenInsertAndUpdateGranted_returnsTrue() {
        MutationBuilder builder = builderWith(Set.of(RlsOp.SELECT, RlsOp.INSERT, RlsOp.UPDATE));

        assertThat(builder.permitsUpsert(TABLE)).isTrue();
    }

    @Test
    void requireSettable_acceptsOnlyTheColumnsThePermissionLists() {
        MutationBuilder builder = builderWith(Set.of(RlsOp.SELECT, RlsOp.INSERT));

        builder.requireSettable(TABLE, RlsOp.INSERT, List.of("id"));
        assertThatThrownBy(() -> builder.requireSettable(TABLE, RlsOp.INSERT, List.of("owner_id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner_id");
        assertThatThrownBy(() -> builder.requireSettable(TABLE, RlsOp.UPDATE, List.of("id")))
                .isInstanceOf(DataErrorException.class)
                .hasMessageContaining("may not be set");
    }
}
