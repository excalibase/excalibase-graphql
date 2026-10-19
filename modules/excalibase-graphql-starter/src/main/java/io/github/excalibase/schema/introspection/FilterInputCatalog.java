package io.github.excalibase.schema.introspection;

import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLScalarType;
import io.github.excalibase.FilterCapabilities;
import io.github.excalibase.FilterColumnKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLFloat;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLString;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_HAS_ANY_KEYS;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_HAS_KEY;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_HAS_KEYS;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_IN;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_IS_NOT_NULL;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_IS_NULL;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_NOT_IN;

/**
 * The shared, schema-wide filter input types, built from a dialect's {@link FilterCapabilities}: a type lists only
 * the operators that database runs, in the engine's schema order, so a client is never offered an operator the
 * compiler would refuse. Per-enum filter types are built in {@link #buildEnumFilters(Map, FilterCapabilities)}
 * because they depend on the enum catalog produced by {@link EnumTypeFactory}.
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
            GraphQLInputObjectType jsonFilter,
            GraphQLInputObjectType fullTextStringFilter,
            Map<String, GraphQLInputObjectType> arrayFilters
    ) {
        /** The filter type of an array column whose elements take {@code element}; null when none is offered. */
        public GraphQLInputObjectType forArray(GraphQLInputType element) {
            return arrayFilters.get(((GraphQLNamedType) element).getName());
        }

        /** The filter type for {@code kind}, or null when the database offers no operator on it. */
        public GraphQLInputObjectType forKind(FilterColumnKind kind) {
            return switch (kind) {
                case STRING -> stringFilter;
                case FULL_TEXT_STRING -> fullTextStringFilter;
                case INT -> intFilter;
                case FLOAT -> floatFilter;
                case BOOLEAN -> booleanFilter;
                case DATE_TIME -> dateTimeFilter;
                case JSON -> jsonFilter;
                case TSVECTOR -> tsvectorFilter;
                case ENUM -> throw new IllegalArgumentException("Enum filters are built per enum type");
                case ARRAY -> throw new IllegalArgumentException("Array filters are built per element type");
            };
        }
    }

    private FilterInputCatalog() {}

    public static FilterInputs inputs(FilterCapabilities capabilities) {
        return new FilterInputs(
                build("StringFilterInput", FilterColumnKind.STRING, GraphQLString, capabilities),
                build("TsvectorFilterInput", FilterColumnKind.TSVECTOR, GraphQLString, capabilities),
                build("IntFilterInput", FilterColumnKind.INT, GraphQLInt, capabilities),
                build("FloatFilterInput", FilterColumnKind.FLOAT, GraphQLFloat, capabilities),
                build("BooleanFilterInput", FilterColumnKind.BOOLEAN, GraphQLBoolean, capabilities),
                build("DateTimeFilterInput", FilterColumnKind.DATE_TIME, GraphQLString, capabilities),
                build("JsonFilterInput", FilterColumnKind.JSON, ExtendedScalars.Json, capabilities),
                build("SearchableStringFilterInput", FilterColumnKind.FULL_TEXT_STRING, GraphQLString, capabilities),
                arrayFilters(capabilities));
    }

    /** One array filter per element scalar, e.g. StringArrayFilterInput; only those a column uses reach the schema. */
    private static Map<String, GraphQLInputObjectType> arrayFilters(FilterCapabilities capabilities) {
        Map<String, GraphQLInputObjectType> filters = new LinkedHashMap<>();
        for (GraphQLScalarType element : List.of(GraphQLString, GraphQLInt, GraphQLFloat, GraphQLBoolean,
                ExtendedScalars.GraphQLBigInteger, ExtendedScalars.Json)) {
            GraphQLInputObjectType filter = build(element.getName() + "ArrayFilterInput", FilterColumnKind.ARRAY,
                    GraphQLList.list(element), capabilities);
            if (filter != null) {
                filters.put(element.getName(), filter);
            }
        }
        return Map.copyOf(filters);
    }

    /** {@code name} holding each operator {@code capabilities} offers on {@code kind}; null when it offers none. */
    private static GraphQLInputObjectType build(String name, FilterColumnKind kind, GraphQLInputType scalar,
                                                FilterCapabilities capabilities) {
        List<String> operators = capabilities.operators(kind);
        if (operators.isEmpty()) return null;
        GraphQLInputObjectType.Builder builder = GraphQLInputObjectType.newInputObject().name(name);
        for (String operator : operators) {
            builder.field(GraphQLInputObjectField.newInputObjectField().name(operator)
                    .type(operandType(operator, scalar)).build());
        }
        return builder.build();
    }

    /**
     * The type an operator takes: a list for {@code in}/{@code notIn} and the key-set operators, a Boolean for
     * the null checks, a key name for {@code hasKey}, otherwise the column's own scalar.
     */
    private static GraphQLInputType operandType(String operator, GraphQLInputType scalar) {
        return switch (operator) {
            case FILTER_IN, FILTER_NOT_IN -> GraphQLList.list(scalar);
            case FILTER_IS_NULL, FILTER_IS_NOT_NULL -> GraphQLBoolean;
            case FILTER_HAS_KEY -> GraphQLString;
            case FILTER_HAS_KEYS, FILTER_HAS_ANY_KEYS -> GraphQLList.list(GraphQLString);
            default -> scalar;
        };
    }

    // Per-enum filter input types: clients get enum-narrowed eq/neq/in/notIn/isNull/isNotNull operators
    // (eq accepts only enum members) instead of a String the database would coerce.
    public static Map<String, GraphQLInputObjectType> buildEnumFilters(Map<String, GraphQLEnumType> enumTypes,
                                                                       FilterCapabilities capabilities) {
        Map<String, GraphQLInputObjectType> enumFilterMap = new LinkedHashMap<>();
        for (Map.Entry<String, GraphQLEnumType> entry : enumTypes.entrySet()) {
            GraphQLEnumType enumType = entry.getValue();
            GraphQLInputObjectType enumFilter = build(enumType.getName() + "FilterInput", FilterColumnKind.ENUM,
                    enumType, capabilities);
            if (enumFilter != null) {
                enumFilterMap.put(entry.getKey(), enumFilter);
            }
        }
        return enumFilterMap;
    }
}
