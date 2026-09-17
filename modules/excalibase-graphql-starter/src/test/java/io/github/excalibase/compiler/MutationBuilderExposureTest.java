package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableExposure;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.spi.MutationCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MutationBuilderExposureTest {

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
        TableExposure exposure = TableExposure.enforcing(Map.of(TABLE, granted));
        return new MutationBuilder(schemaInfo, dialect, filterBuilder, "public",
                queryBuilder, mutationCompiler, exposure);
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
    void resolveMutationTable_whenExposureUnrestricted_resolvesEveryMutationField() {
        MutationBuilder builder = new MutationBuilder(schemaInfo, dialect, filterBuilder, "public",
                queryBuilder, mutationCompiler, TableExposure.UNRESTRICTED);

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
}
