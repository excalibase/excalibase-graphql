package io.github.excalibase.schema.introspection;

import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;

import java.util.LinkedHashMap;
import java.util.Map;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLFloat;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLString;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_CONTAINED_BY;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_CONTAINS;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_ENDS_WITH;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_EQ;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_GT;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_GTE;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_HAS_ANY_KEYS;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_HAS_KEY;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_HAS_KEYS;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_ILIKE;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_IN;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_IREGEX;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_IS_NOT_NULL;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_IS_NULL;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_LIKE;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_LT;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_LTE;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_NEQ;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_NOT_IN;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_PHRASE_SEARCH;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_RAW_SEARCH;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_REGEX;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_SEARCH;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_STARTS_WITH;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_WEB_SEARCH;

/**
 * Immutable catalog of the shared, schema-wide filter input types.
 * Per-enum filter types are built in {@link #buildEnumFilters(Map)} because
 * they depend on the enum catalog produced by {@link EnumTypeFactory}.
 *
 * <p>Operators listed here must stay in sync with the dispatching switch in
 * {@code FilterBuilder}; drift causes clients to receive operators the
 * server silently ignores.
 */
public final class FilterInputCatalog {

    /**
     * Bundle of the shared filter input types, one per column-type category.
     * Held as a record so factories can receive all inputs in a single arg.
     */
    public record FilterInputs(
            GraphQLInputObjectType stringFilter,
            GraphQLInputObjectType tsvectorFilter,
            GraphQLInputObjectType intFilter,
            GraphQLInputObjectType floatFilter,
            GraphQLInputObjectType booleanFilter,
            GraphQLInputObjectType dateTimeFilter,
            GraphQLInputObjectType jsonFilter
    ) {}

    public static final FilterInputs INPUTS = new FilterInputs(
            buildStringFilter(),
            buildTsvectorFilter(),
            buildIntFilter(),
            buildFloatFilter(),
            buildBooleanFilter(),
            buildDateTimeFilter(),
            buildJsonFilter()
    );

    private FilterInputCatalog() {}

    // String filter input -- full operator set supported by FilterBuilder.
    // Operators listed here must be handled by the dispatching switch in
    // FilterBuilder; drift causes clients to receive operators the server
    // silently ignores.
    private static GraphQLInputObjectType buildStringFilter() {
        return GraphQLInputObjectType.newInputObject()
                .name("StringFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LIKE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_ILIKE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_CONTAINS).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_STARTS_WITH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_ENDS_WITH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_REGEX).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IREGEX).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();
    }

    // Tsvector filter input — two operators, both dispatched via
    // SqlDialect.fullTextSearchSql. Separate from StringFilterInput so
    // plain text columns don't accidentally expose the FTS surface
    // (which would emit invalid SQL against a non-tsvector column).
    //   search:    plainto_tsquery — raw user text, safe on any input
    //   webSearch: websearch_to_tsquery — Google-style "phrase" / OR / -
    private static GraphQLInputObjectType buildTsvectorFilter() {
        return GraphQLInputObjectType.newInputObject()
                .name("TsvectorFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_SEARCH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_WEB_SEARCH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_PHRASE_SEARCH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_RAW_SEARCH).type(GraphQLString).build())
                .build();
    }

    private static GraphQLInputObjectType buildIntFilter() {
        return GraphQLInputObjectType.newInputObject()
                .name("IntFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GT).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GTE).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LT).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LTE).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(GraphQLInt)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(GraphQLInt)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();
    }

    // Float filter input — numeric, decimal, real, double precision
    // columns get this so clients can filter with fractional values.
    // Previously these fell through to IntFilterInput which forced Int
    // binds and rejected decimals like `{ lt: 9.99 }`.
    private static GraphQLInputObjectType buildFloatFilter() {
        return GraphQLInputObjectType.newInputObject()
                .name("FloatFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GT).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GTE).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LT).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LTE).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(GraphQLFloat)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(GraphQLFloat)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();
    }

    // Boolean filter input — boolean columns get this. Only equality and
    // null checks make sense (there's no "greater than false").
    private static GraphQLInputObjectType buildBooleanFilter() {
        return GraphQLInputObjectType.newInputObject()
                .name("BooleanFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();
    }

    // DateTime filter input — timestamp, timestamptz, date, time, timetz
    // columns. Values are strings on the wire (ISO 8601 by convention)
    // and Postgres coerces them via paramCast. Supports the full range +
    // equality + list suite so users can filter by time windows.
    private static GraphQLInputObjectType buildDateTimeFilter() {
        return GraphQLInputObjectType.newInputObject()
                .name("DateTimeFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GT).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GTE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LT).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LTE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();
    }

    // JSONB filter input. Each operator maps to a Postgres jsonb op or
    // function — see PostgresDialect.jsonPredicateSql for the full list.
    // The input values use graphql-java's ExtendedScalars.Json so
    // ObjectValue / ArrayValue arguments parse correctly; FilterBuilder
    // serializes them to compact JSON strings for Postgres to parse.
    private static GraphQLInputObjectType buildJsonFilter() {
        return GraphQLInputObjectType.newInputObject()
                .name("JsonFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(ExtendedScalars.Json).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(ExtendedScalars.Json).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_CONTAINS).type(ExtendedScalars.Json).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_CONTAINED_BY).type(ExtendedScalars.Json).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_HAS_KEY).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_HAS_KEYS).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_HAS_ANY_KEYS).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();
    }

    // Per-enum filter input types. Enum columns (e.g. Postgres
    // `CREATE TYPE issue_status AS ENUM(...)`) were previously filtered
    // via StringFilterInput, which meant clients had to pass a plain
    // String and the server relied on the DB to coerce. That silently
    // accepts any string (losing compile-time safety) and prevents
    // autocomplete on enum values in codegen-generated clients.
    // Building a per-enum filter type gives clients enum-narrowed
    // eq/neq/in/notIn/isNull/isNotNull operators.
    public static Map<String, GraphQLInputObjectType> buildEnumFilters(Map<String, GraphQLEnumType> enumTypes) {
        Map<String, GraphQLInputObjectType> enumFilterMap = new LinkedHashMap<>();
        for (Map.Entry<String, GraphQLEnumType> entry : enumTypes.entrySet()) {
            GraphQLEnumType enumType = entry.getValue();
            GraphQLInputObjectType enumFilter = GraphQLInputObjectType.newInputObject()
                    .name(enumType.getName() + "FilterInput")
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(enumType).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(enumType).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(enumType)).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(enumType)).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                    .build();
            enumFilterMap.put(entry.getKey(), enumFilter);
        }
        return enumFilterMap;
    }
}
