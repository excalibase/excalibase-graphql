package io.github.excalibase.config;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.SqlEngineProvider;
import io.github.excalibase.spi.MutationCompiler;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.spi.SchemaLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A slim image refuses, at start, a database it does not carry, and names the image to use (EXC-799). */
class EngineAvailabilityGuardTest {

    private static final String NO_TENANTS = "";

    private static SqlEngineProvider provider(String... types) {
        return new SqlEngineProvider() {
            @Override public List<String> supportedTypes() { return List.of(types); }
            @Override public SqlDialect createDialect() { throw new UnsupportedOperationException(); }
            @Override public SchemaLoader createSchemaLoader() { throw new UnsupportedOperationException(); }
            @Override public MutationCompiler createMutationCompiler() { throw new UnsupportedOperationException(); }
            @Override public MutationExecutor createMutationExecutor() { throw new UnsupportedOperationException(); }
        };
    }

    private static final List<SqlEngineProvider> POSTGRES_ONLY = List.of(provider("postgres", "postgresql"));
    private static final List<SqlEngineProvider> MYSQL_ONLY = List.of(provider("mysql"));
    private static final List<SqlEngineProvider> BOTH = List.of(provider("postgres", "postgresql"), provider("mysql"));

    @Test
    void aSlimBuildServesItsOwnDatabase() {
        assertThatCode(() -> new EngineAvailabilityGuard("postgres", "jdbc:postgresql://db/app", NO_TENANTS).check(POSTGRES_ONLY))
                .doesNotThrowAnyException();
        assertThatCode(() -> new EngineAvailabilityGuard("mysql", "jdbc:mysql://db/app", NO_TENANTS).check(MYSQL_ONLY))
                .doesNotThrowAnyException();
    }

    @Test
    void theAllInOneBuildServesEither() {
        assertThatCode(() -> new EngineAvailabilityGuard("postgres", "jdbc:postgresql://db/app", NO_TENANTS).check(BOTH))
                .doesNotThrowAnyException();
        assertThatCode(() -> new EngineAvailabilityGuard("mysql", "jdbc:mysql://db/app", NO_TENANTS).check(BOTH))
                .doesNotThrowAnyException();
    }

    @Test
    void aPostgresBuildRefusesAMysqlDatabaseAndNamesTheMysqlImage() {
        assertThatThrownBy(() -> new EngineAvailabilityGuard("mysql", "jdbc:mysql://db/app", NO_TENANTS).check(POSTGRES_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excalibase/excalibase-graphql:<tag>-mysql");
    }

    @Test
    void aMysqlBuildRefusesAPostgresDatabaseAndNamesThePostgresImage() {
        assertThatThrownBy(() -> new EngineAvailabilityGuard("postgres", "jdbc:postgresql://db/app", NO_TENANTS).check(MYSQL_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excalibase/excalibase-graphql:<tag>-postgres");
    }

    @Test
    void aUrlOfAnotherDatabaseIsRefusedEvenWhenTheTypeIsLeftAtItsDefault() {
        assertThatThrownBy(() -> new EngineAvailabilityGuard("postgres", "jdbc:mysql://db/app", NO_TENANTS).check(POSTGRES_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:mysql")
                .hasMessageContaining("<tag>-mysql");
    }

    @Test
    void theRefusalSaysWhichSettingItJudged() {
        assertThatThrownBy(() -> new EngineAvailabilityGuard("mysql", "", NO_TENANTS).check(POSTGRES_ONLY))
                .hasMessageContaining("APP_DATABASE_TYPE=mysql");
    }

    @Test
    void aUrlOfADatabaseNoImageKnowsIsLeftToItsOwnPlugin() {
        assertThatCode(() -> new EngineAvailabilityGuard("sqlite", "jdbc:sqlite:/data/app.db", NO_TENANTS)
                .check(List.of(provider("sqlite")))).doesNotThrowAnyException();
    }

    @Test
    void multiTenantModeHasNoDefaultDatabaseToJudge() {
        assertThatCode(() -> new EngineAvailabilityGuard("postgres", "", "http://provisioning").check(MYSQL_ONLY))
                .doesNotThrowAnyException();
    }
}
