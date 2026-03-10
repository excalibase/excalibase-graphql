package io.github.excalibase.config;

import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.model.*;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * GraalVM Native Image runtime hints for excalibase-graphql.
 * Registers all reflection-heavy classes so the native binary can access them at runtime.
 */
public class ExcalibaseRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        registerModelClasses(hints);
        registerAnnotations(hints);
        registerGraphQLJavaClasses(hints);
        registerJdbcClasses(hints);
        registerJacksonClasses(hints);
        registerResources(hints);
    }

    private void registerModelClasses(RuntimeHints hints) {
        MemberCategory[] all = {
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.PUBLIC_FIELDS,
            MemberCategory.DECLARED_FIELDS
        };

        List<Class<?>> modelClasses = List.of(
            TableInfo.class,
            ColumnInfo.class,
            ForeignKeyInfo.class,
            CustomEnumInfo.class,
            CustomCompositeTypeInfo.class,
            CompositeTypeAttribute.class,
            ComputedFieldFunction.class,
            RlsPolicy.class,
            RolePrivileges.class,
            AppConfig.class,
            AppConfig.SecurityConfig.class,
            AppConfig.CacheConfig.class
        );

        for (Class<?> cls : modelClasses) {
            hints.reflection().registerType(cls, all);
        }
    }

    private void registerAnnotations(RuntimeHints hints) {
        hints.reflection().registerType(ExcalibaseService.class,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.INVOKE_DECLARED_METHODS);
    }

    private void registerGraphQLJavaClasses(RuntimeHints hints) {
        MemberCategory[] publicOnly = {
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.PUBLIC_FIELDS
        };

        // graphql-java core schema types
        String[] graphqlClasses = {
            "graphql.schema.GraphQLSchema",
            "graphql.schema.GraphQLObjectType",
            "graphql.schema.GraphQLInputObjectType",
            "graphql.schema.GraphQLEnumType",
            "graphql.schema.GraphQLEnumValueDefinition",
            "graphql.schema.GraphQLFieldDefinition",
            "graphql.schema.GraphQLInputObjectField",
            "graphql.schema.GraphQLList",
            "graphql.schema.GraphQLNonNull",
            "graphql.schema.GraphQLScalarType",
            "graphql.schema.GraphQLTypeReference",
            "graphql.schema.GraphQLArgument",
            "graphql.schema.GraphQLCodeRegistry",
            "graphql.schema.DataFetchingEnvironmentImpl",
            "graphql.schema.PropertyDataFetcher",
            "graphql.execution.ExecutionResult",
            "graphql.execution.ExecutionResultImpl",
            "graphql.execution.CoercedVariables",
            "graphql.execution.NonNullableFieldWasNullException",
            "graphql.execution.instrumentation.SimplePerformantInstrumentation",
            "graphql.ErrorType",
            "graphql.GraphqlErrorBuilder",
            "graphql.GraphQLError",
            // language AST value types used in coercion
            "graphql.language.StringValue",
            "graphql.language.IntValue",
            "graphql.language.FloatValue",
            "graphql.language.BooleanValue",
            "graphql.language.EnumValue",
            "graphql.language.NullValue",
            "graphql.language.ArrayValue",
            "graphql.language.ObjectValue",
            "graphql.language.ObjectField",
            "graphql.language.VariableReference",
            "graphql.language.ListType",
            "graphql.language.NonNullType",
            "graphql.language.TypeName",
            // Scalars
            "graphql.Scalars",
            "graphql.scalars.ExtendedScalars",
            "graphql.scalar.CoercingParseLiteralException",
            "graphql.schema.CoercingParseLiteralException",
            "graphql.schema.CoercingParseValueException",
            "graphql.schema.CoercingSerializeException",
        };

        for (String className : graphqlClasses) {
            try {
                Class<?> cls = Class.forName(className);
                hints.reflection().registerType(cls, publicOnly);
            } catch (ClassNotFoundException ignored) {
                // class may not exist in this version — skip
            }
        }
    }

    private void registerJdbcClasses(RuntimeHints hints) {
        MemberCategory[] all = {
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.DECLARED_FIELDS
        };

        String[] jdbcClasses = {
            "org.postgresql.jdbc.PgArray",
            "org.postgresql.jdbc.PgConnection",
            "org.postgresql.jdbc.PgResultSet",
            "org.postgresql.jdbc.PgStatement",
            "org.postgresql.Driver",
            "org.postgresql.ds.PGSimpleDataSource",
            // PGobject is used for JSON/JSONB, BIT, and other custom types
            "org.postgresql.util.PGobject",
        };

        for (String className : jdbcClasses) {
            try {
                Class<?> cls = Class.forName(className);
                hints.reflection().registerType(cls, all);
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private void registerJacksonClasses(RuntimeHints hints) {
        MemberCategory[] all = {
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.DECLARED_FIELDS
        };

        // Jackson container types used by JsonScalar and JsonUtil
        hints.reflection().registerType(LinkedHashMap.class, all);
        hints.reflection().registerType(HashMap.class, all);
        hints.reflection().registerType(ArrayList.class, all);

        String[] jacksonClasses = {
            "com.fasterxml.jackson.databind.node.ObjectNode",
            "com.fasterxml.jackson.databind.node.ArrayNode",
            "com.fasterxml.jackson.databind.node.TextNode",
            "com.fasterxml.jackson.databind.node.IntNode",
            "com.fasterxml.jackson.databind.node.LongNode",
            "com.fasterxml.jackson.databind.node.DoubleNode",
            "com.fasterxml.jackson.databind.node.BooleanNode",
            "com.fasterxml.jackson.databind.node.NullNode",
            "com.fasterxml.jackson.databind.node.BigIntegerNode",
            "com.fasterxml.jackson.databind.node.DecimalNode",
            "com.fasterxml.jackson.databind.JsonNode",
        };

        for (String className : jacksonClasses) {
            try {
                Class<?> cls = Class.forName(className);
                hints.reflection().registerType(cls, all);
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private void registerResources(RuntimeHints hints) {
        hints.resources().registerPattern("application.yaml");
        hints.resources().registerPattern("application-*.yaml");
        hints.resources().registerPattern("application.properties");
        hints.resources().registerPattern("application-*.properties");
        hints.resources().registerPattern("*.sql");
        hints.resources().registerPattern("graphql/*.graphqls");
        hints.resources().registerPattern("graphql/*.graphql");
    }
}
