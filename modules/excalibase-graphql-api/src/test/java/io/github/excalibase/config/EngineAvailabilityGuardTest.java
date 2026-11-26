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
        EngineAvailabilityGuard postgresOnPostgresBuild = new EngineAvailabilityGuard("postgres", "jdbc:postgresql://db/app", NO_TENANTS);
        assertThatCode(() -> postgresOnPostgresBuild.check(POSTGRES_ONLY))
                .doesNotThrowAnyException();
        EngineAvailabilityGuard mysqlOnMysqlBuild = new EngineAvailabilityGuard("mysql", "jdbc:mysql://db/app", NO_TENANTS);
        assertThatCode(() -> mysqlOnMysqlBuild.check(MYSQL_ONLY))
                .doesNotThrowAnyException();
    }

    @Test
    void theAllInOneBuildServesEither() {
        EngineAvailabilityGuard postgresOnAllInOne = new EngineAvailabilityGuard("postgres", "jdbc:postgresql://db/app", NO_TENANTS);
        assertThatCode(() -> postgresOnAllInOne.check(BOTH))
                .doesNotThrowAnyException();
        EngineAvailabilityGuard mysqlOnAllInOne = new EngineAvailabilityGuard("mysql", "jdbc:mysql://db/app", NO_TENANTS);
        assertThatCode(() -> mysqlOnAllInOne.check(BOTH))
                .doesNotThrowAnyException();
    }

    @Test
    void aDocumentDatabaseNeedsNoSqlProviderAndNoJdbcUrl() {
        EngineAvailabilityGuard mongodb = new EngineAvailabilityGuard("mongodb", "", NO_TENANTS);
        assertThatCode(() -> mongodb.check(POSTGRES_ONLY)).doesNotThrowAnyException();
        EngineAvailabilityGuard documentdb = new EngineAvailabilityGuard("DocumentDB", null, NO_TENANTS);
        assertThatCode(() -> documentdb.check(MYSQL_ONLY)).doesNotThrowAnyException();
    }

    @Test
    void aPostgresBuildRefusesAMysqlDatabaseAndNamesTheMysqlImage() {
        EngineAvailabilityGuard guard = new EngineAvailabilityGuard("mysql", "jdbc:mysql://db/app", NO_TENANTS);
        assertThatThrownBy(() -> guard.check(POSTGRES_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excalibase/excalibase-graphql:<tag>-mysql");
    }

    @Test
    void aMysqlBuildRefusesAPostgresDatabaseAndNamesThePostgresImage() {
        EngineAvailabilityGuard guard = new EngineAvailabilityGuard("postgres", "jdbc:postgresql://db/app", NO_TENANTS);
        assertThatThrownBy(() -> guard.check(MYSQL_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excalibase/excalibase-graphql:<tag>-postgres");
    }

    @Test
    void aUrlOfAnotherDatabaseIsRefusedEvenWhenTheTypeIsLeftAtItsDefault() {
        EngineAvailabilityGuard guard = new EngineAvailabilityGuard("postgres", "jdbc:mysql://db/app", NO_TENANTS);
        assertThatThrownBy(() -> guard.check(POSTGRES_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:mysql")
                .hasMessageContaining("<tag>-mysql");
    }

    @Test
    void theRefusalSaysWhichSettingItJudged() {
        EngineAvailabilityGuard guard = new EngineAvailabilityGuard("mysql", "", NO_TENANTS);
        assertThatThrownBy(() -> guard.check(POSTGRES_ONLY))
                .hasMessageContaining("APP_DATABASE_TYPE=mysql");
    }

    @Test
    void aUrlOfADatabaseNoImageKnowsIsLeftToItsOwnPlugin() {
        EngineAvailabilityGuard guard = new EngineAvailabilityGuard("sqlite", "jdbc:sqlite:/data/app.db", NO_TENANTS);
        List<SqlEngineProvider> carried = List.of(provider("sqlite"));
        assertThatCode(() -> guard.check(carried)).doesNotThrowAnyException();
    }

    @Test
    void multiTenantModeHasNoDefaultDatabaseToJudge() {
        EngineAvailabilityGuard multiTenantOnMysqlBuild = new EngineAvailabilityGuard("postgres", "", "http://provisioning");
        assertThatCode(() -> multiTenantOnMysqlBuild.check(MYSQL_ONLY))
                .doesNotThrowAnyException();
    }

    @Test
    void theDbRuleSourceIsRefusedOnABuildWithoutPostgres() {
        EngineAvailabilityGuard guard = new EngineAvailabilityGuard("mysql", "jdbc:mysql://db/app", NO_TENANTS, "db");
        assertThatThrownBy(() -> guard.check(MYSQL_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("METADATA_SOURCE=db")
                .hasMessageContaining("<tag>-postgres");
    }

    @Test
    void theDbRuleSourceIsRefusedInMultiTenantModeToo() {
        EngineAvailabilityGuard guard = new EngineAvailabilityGuard("mysql", "", "http://provisioning", "db");
        assertThatThrownBy(() -> guard.check(MYSQL_ONLY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void otherRuleSourcesAndTheDbSourceOnPostgresStart() {
        EngineAvailabilityGuard fileSourceOnMysqlBuild = new EngineAvailabilityGuard("mysql", "jdbc:mysql://db/app", NO_TENANTS, "file");
        assertThatCode(() -> fileSourceOnMysqlBuild.check(MYSQL_ONLY))
                .doesNotThrowAnyException();
        EngineAvailabilityGuard dbSourceOnPostgresBuild = new EngineAvailabilityGuard("postgres", "jdbc:postgresql://db/app", NO_TENANTS, "db");
        assertThatCode(() -> dbSourceOnPostgresBuild.check(POSTGRES_ONLY))
                .doesNotThrowAnyException();
        EngineAvailabilityGuard dbSourceOnAllInOne = new EngineAvailabilityGuard("mysql", "jdbc:mysql://db/app", NO_TENANTS, "db");
        assertThatCode(() -> dbSourceOnAllInOne.check(BOTH))
                .doesNotThrowAnyException();
    }
}
