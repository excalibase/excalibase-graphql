package io.github.excalibase;

import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.mysql.MysqlDialect;
import io.github.excalibase.mysql.MysqlSchemaLoader;
import io.github.excalibase.postgres.PostgresDialect;
import io.github.excalibase.postgres.PostgresSchemaLoader;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.SchemaLoader;
import io.github.excalibase.spi.SqlEngine;
import io.github.excalibase.spi.SqlEngineFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SqlEngineFactory — factory pattern to create correct
 * dialect and schema loader per DB type.
 */
class SqlEngineFactoryTest {

    @Test
    void createPostgresEngine() {
        SqlEngine engine = SqlEngineFactory.create("postgres");
        assertNotNull(engine);
        assertInstanceOf(PostgresDialect.class, engine.dialect());
        assertInstanceOf(PostgresSchemaLoader.class, engine.schemaLoader());
    }

    @Test
    void createMysqlEngine() {
        SqlEngine engine = SqlEngineFactory.create("mysql");
        assertNotNull(engine);
        assertInstanceOf(MysqlDialect.class, engine.dialect());
        assertInstanceOf(MysqlSchemaLoader.class, engine.schemaLoader());
    }

    @Test
    void caseInsensitiveDatabaseType() {
        SqlEngine pgUpper = SqlEngineFactory.create("POSTGRES");
        assertInstanceOf(PostgresDialect.class, pgUpper.dialect());

        SqlEngine mysqlMixed = SqlEngineFactory.create("MySQL");
        assertInstanceOf(MysqlDialect.class, mysqlMixed.dialect());
    }

    @Test
    void unknownDatabaseTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> SqlEngineFactory.create("mssql"));
    }

    // === Dialect contracts ===

    @Test
    void postgresDialectSupportsReturning() {
        SqlEngine engine = SqlEngineFactory.create("postgres");
        assertTrue(engine.dialect().supportsReturning());
    }

    @Test
    void mysqlDialectDoesNotSupportReturning() {
        SqlEngine engine = SqlEngineFactory.create("mysql");
        assertFalse(engine.dialect().supportsReturning());
    }

    // === SchemaLoader contract ===

    @Test
    void schemaLoaderInterface() {
        SqlEngine engine = SqlEngineFactory.create("postgres");
        SchemaLoader loader = engine.schemaLoader();
        assertNotNull(loader);
    }

    // === Query depth limit ===

    @Test
    void depthLimitDisabledByDefault() {
        SqlEngine engine = SqlEngineFactory.create("postgres");
        SchemaInfo schema = new SchemaInfo();
        SqlCompiler compiler = new SqlCompiler(schema, "test", 30, engine.dialect(), engine.mutationCompiler());
        assertDoesNotThrow(() -> compiler.isIntrospection("{ __schema { queryType { name } } }"));
    }

    @Test
    void depthLimitRejectsDeepQuery() {
        SqlEngine engine = SqlEngineFactory.create("postgres");
        SchemaInfo schema = new SchemaInfo();
        SqlCompiler compiler = new SqlCompiler(schema, "test", 30, engine.dialect(), engine.mutationCompiler(), 3);
        assertThrows(IllegalArgumentException.class, () ->
                compiler.compile("{ a { b { c { d } } } }"));
    }

    @Test
    void depthLimitAllowsShallowQuery() {
        SqlEngine engine = SqlEngineFactory.create("postgres");
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn("customer", "customer_id", "integer");
        schema.addPrimaryKey("customer", "customer_id");
        SqlCompiler compiler = new SqlCompiler(schema, "test", 30, engine.dialect(), engine.mutationCompiler(), 5);
        assertDoesNotThrow(() -> compiler.compile("{ customer { customer_id } }"));
    }

    // === Default query-depth limit (app.max-query-depth unset -> DEFAULT_MAX_QUERY_DEPTH) ===

    @Test
    void defaultDepthLimitIsFifteen() {
        assertEquals(15, GraphqlSchemaManager.DEFAULT_MAX_QUERY_DEPTH);
    }

    @Test
    void defaultDepthRejectsQueryDeeperThanLimit() {
        SqlEngine engine = SqlEngineFactory.create("postgres");
        SqlCompiler compiler = new SqlCompiler(new SchemaInfo(), "test", 30,
                engine.dialect(), engine.mutationCompiler(), GraphqlSchemaManager.DEFAULT_MAX_QUERY_DEPTH);
        String tooDeep = nestedQuery(GraphqlSchemaManager.DEFAULT_MAX_QUERY_DEPTH + 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(tooDeep));
        assertTrue(ex.getMessage().contains("exceeds maximum allowed depth"));
    }

    @Test
    void defaultDepthAllowsQueryAtLimit() {
        SqlEngine engine = SqlEngineFactory.create("postgres");
        SqlCompiler compiler = new SqlCompiler(new SchemaInfo(), "test", 30,
                engine.dialect(), engine.mutationCompiler(), GraphqlSchemaManager.DEFAULT_MAX_QUERY_DEPTH);
        String atLimit = nestedQuery(GraphqlSchemaManager.DEFAULT_MAX_QUERY_DEPTH);
        // The depth gate must pass at the limit; the unknown-field failure against an
        // empty schema proves the query got past the depth check rather than being rejected for depth.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(atLimit));
        assertFalse(ex.getMessage().contains("exceeds maximum allowed depth"));
    }

    /** Builds a query nested exactly {@code depth} selection-set levels deep: {@code { f0 { f1 { f2 } } }} is depth 3. */
    private static String nestedQuery(int depth) {
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = 0; i < depth - 1; i++) {
            sb.append("f").append(i).append(" { ");
        }
        sb.append("f").append(depth - 1);
        for (int i = 0; i < depth - 1; i++) {
            sb.append(" }");
        }
        return sb.append(" }").toString();
    }
}
