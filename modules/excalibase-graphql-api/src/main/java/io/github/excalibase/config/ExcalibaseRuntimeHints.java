package io.github.excalibase.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * GraalVM Native Image runtime hints for the SQL compiler engine.
 * Registers reflection-heavy classes so the native binary can access them at runtime.
 */
public class ExcalibaseRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        MemberCategory[] all = {
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.PUBLIC_FIELDS,
                MemberCategory.DECLARED_FIELDS
        };

        // SPI providers (ServiceLoader)
        registerClass(hints, "io.github.excalibase.postgres.PostgresEngineProvider", all);
        registerClass(hints, "io.github.excalibase.postgres.PostgresDialect", all);
        registerClass(hints, "io.github.excalibase.postgres.PostgresSchemaLoader", all);
        registerClass(hints, "io.github.excalibase.postgres.PostgresMutationCompiler", all);
        registerClass(hints, "io.github.excalibase.postgres.PostgresMutationExecutor", all);
        registerClass(hints, "io.github.excalibase.mysql.MysqlEngineProvider", all);
        registerClass(hints, "io.github.excalibase.mysql.MysqlDialect", all);
        registerClass(hints, "io.github.excalibase.mysql.MysqlSchemaLoader", all);
        registerClass(hints, "io.github.excalibase.mysql.MysqlMutationCompiler", all);
        registerClass(hints, "io.github.excalibase.mysql.MysqlMutationExecutor", all);

        // Core classes used via reflection
        registerClass(hints, "io.github.excalibase.cdc.CDCEvent", all);
        registerClass(hints, "io.github.excalibase.cdc.NatsCDCService", all);
        registerClass(hints, "io.github.excalibase.cdc.SubscriptionService", all);
        registerClass(hints, "io.github.excalibase.config.ws.GraphQLWebSocketHandler", all);

        // JWT security
        registerClass(hints, "io.github.excalibase.security.JwtAuthFilter", all);
        registerClass(hints, "io.github.excalibase.security.JwtService", all);
        registerClass(hints, "io.github.excalibase.security.JwtClaims", all);
        registerClass(hints, "io.github.excalibase.config.SecurityProperties", all);
        registerClass(hints, "io.github.excalibase.config.SecurityProperties$Auth", all);
        registerClass(hints, "io.github.excalibase.config.SecurityProperties$MultiTenant", all);

        // Schema + execution
        registerClass(hints, "io.github.excalibase.schema.GraphqlSchemaManager", all);
        registerClass(hints, "io.github.excalibase.service.QueryExecutionService", all);

        // GraphQL-Java AST types used in SQL compilation
        String[] graphqlClasses = {
                "graphql.language.StringValue", "graphql.language.IntValue",
                "graphql.language.FloatValue", "graphql.language.BooleanValue",
                "graphql.language.EnumValue", "graphql.language.NullValue",
                "graphql.language.ArrayValue", "graphql.language.ObjectValue",
                "graphql.language.ObjectField", "graphql.language.VariableReference",
                "graphql.schema.GraphQLSchema", "graphql.schema.GraphQLObjectType",
                "graphql.schema.GraphQLInputObjectType", "graphql.schema.GraphQLEnumType",
                "graphql.schema.GraphQLFieldDefinition", "graphql.schema.GraphQLArgument",
                "graphql.schema.GraphQLList", "graphql.schema.GraphQLNonNull",
                "graphql.schema.GraphQLScalarType", "graphql.schema.GraphQLTypeReference",
                "graphql.execution.ExecutionResultImpl",
        };
        MemberCategory[] publicOnly = {
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.PUBLIC_FIELDS
        };
        for (String className : graphqlClasses) {
            registerClass(hints, className, publicOnly);
        }

        // Nimbus JOSE+JWT (JWT verification)
        registerClass(hints, "com.nimbusds.jwt.SignedJWT", all);
        registerClass(hints, "com.nimbusds.jwt.JWTClaimsSet", all);
        registerClass(hints, "com.nimbusds.jose.crypto.ECDSAVerifier", all);
        registerClass(hints, "com.nimbusds.jose.JWSHeader", all);
        registerClass(hints, "com.nimbusds.jose.util.Base64URL", all);
        registerClass(hints, "com.nimbusds.jose.jwk.JWKSet", all);
        registerClass(hints, "com.nimbusds.jose.jwk.ECKey", all);

        // JDBC drivers
        registerClass(hints, "org.postgresql.Driver", all);
        registerClass(hints, "org.postgresql.util.PGobject", all);
        registerClass(hints, "org.postgresql.jdbc.PgArray", all);

        // NATS
        registerClass(hints, "io.nats.client.impl.SocketDataPort", all);
        registerClass(hints, "io.nats.client.support.JsonValue", all);

        // Resources
        hints.resources().registerPattern("META-INF/services/*");
        hints.resources().registerPattern("application.yaml");
        hints.resources().registerPattern("application-*.yaml");
    }

    private void registerClass(RuntimeHints hints, String className, MemberCategory[] categories) {
        try {
            hints.reflection().registerType(Class.forName(className), categories);
        } catch (ClassNotFoundException ignored) {
        }
    }
}
