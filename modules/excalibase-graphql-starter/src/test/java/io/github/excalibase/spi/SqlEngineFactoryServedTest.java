package io.github.excalibase.spi;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.SqlEngineProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A slim engine image carries one database plugin; asking it for another must be refused in plain words (EXC-799). */
class SqlEngineFactoryServedTest {

    private static SqlEngineProvider provider(String... types) {
        return new SqlEngineProvider() {
            @Override public List<String> supportedTypes() { return List.of(types); }
            @Override public SqlDialect createDialect() { throw new UnsupportedOperationException(); }
            @Override public SchemaLoader createSchemaLoader() { throw new UnsupportedOperationException(); }
            @Override public MutationCompiler createMutationCompiler() { throw new UnsupportedOperationException(); }
            @Override public MutationExecutor createMutationExecutor() { throw new UnsupportedOperationException(); }
        };
    }

    @Test
    void servedTypesAreTheFirstNameOfEachPlugin() {
        List<SqlEngineProvider> carried = List.of(provider("postgres", "postgresql"), provider("mysql"));

        assertThat(SqlEngineFactory.servedTypes(carried)).containsExactly("postgres", "mysql");
    }

    @Test
    void aCarriedTypeIsServedByAnyOfItsNamesInAnyCase() {
        List<SqlEngineProvider> carried = List.of(provider("postgres", "postgresql"));

        assertThatCode(() -> SqlEngineFactory.requireServed("PostgreSQL", carried)).doesNotThrowAnyException();
        assertThatCode(() -> SqlEngineFactory.requireServed("postgres", carried)).doesNotThrowAnyException();
    }

    @Test
    void aTypeThePluginsDoNotCarryIsRefusedWithTheImageToUse() {
        List<SqlEngineProvider> carried = List.of(provider("postgres", "postgresql"));

        assertThatThrownBy(() -> SqlEngineFactory.requireServed("mysql", carried))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serves only postgres")
                .hasMessageContaining("excalibase/excalibase-graphql:<tag>-mysql")
                .hasMessageContaining("all-in-one image excalibase/excalibase-graphql:<tag>");
    }

    @Test
    void anAliasNamesTheSameImageAsTheTypeItStandsFor() {
        List<SqlEngineProvider> carried = List.of(provider("mysql"));

        assertThatThrownBy(() -> SqlEngineFactory.requireServed("postgresql", carried))
                .hasMessageContaining("excalibase/excalibase-graphql:<tag>-postgres")
                .hasMessageNotContaining("-postgresql");
    }

    @Test
    void aTypeNoImageServesIsRefusedWithoutNamingAnImage() {
        List<SqlEngineProvider> carried = List.of(provider("postgres"), provider("mysql"));

        assertThatThrownBy(() -> SqlEngineFactory.requireServed("mssql", carried))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported database type: mssql")
                .hasMessageContaining("postgres, mysql")
                .hasMessageNotContaining("-mssql");
    }

    @Test
    void aBuildWithoutAnyPluginSaysSo() {
        assertThatThrownBy(() -> SqlEngineFactory.requireServed("postgres", List.of()))
                .hasMessageContaining("no database plugin");
    }

    @Test
    void aBlankTypeIsRefused() {
        List<SqlEngineProvider> carried = List.of(provider("postgres"));

        assertThatThrownBy(() -> SqlEngineFactory.requireServed(" ", carried))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
