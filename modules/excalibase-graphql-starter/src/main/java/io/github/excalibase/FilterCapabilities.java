package io.github.excalibase;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * The filter operators a database runs, per column kind (GraphQL names, in the order the schema lists them) and
 * for REST (PostgREST names). The schema advertises exactly these, and the compilers refuse anything else.
 */
public interface FilterCapabilities {

    /** Every GraphQL operator the engine knows for {@code kind}, in schema order. */
    static List<String> known(FilterColumnKind kind) {
        return Known.GRAPHQL.get(kind);
    }

    /** Every REST operator the engine parses. */
    static Set<String> knownRest() {
        return Known.REST;
    }

    /** Everything the engine knows: what Postgres runs. */
    FilterCapabilities ALL = of(Known.GRAPHQL, Known.REST);

    /** What any SQL database runs: comparisons, lists, LIKE and null checks; no regex, full text or JSON. */
    FilterCapabilities PORTABLE = of(Known.portable(), Set.of("eq", "neq", "gt", "gte", "lt", "lte", "like",
            "ilike", "startswith", "endswith", "in", "notin", "is", "isnotnull"));

    /** The operators on {@code kind}, in schema order; empty when the kind cannot be filtered. */
    List<String> operators(FilterColumnKind kind);

    /** The REST operators this database runs. */
    Set<String> restOperators();

    default boolean supports(FilterColumnKind kind, String operator) {
        return operators(kind).contains(operator);
    }

    /** Why {@code operator} is not offered on {@code kind}, appended to the refusal; empty when there is nothing to add. */
    default String unsupportedReason(FilterColumnKind kind, String operator) {
        return "";
    }

    /** Capabilities from a fixed table; a kind missing from {@code byKind} offers no operator. */
    static FilterCapabilities of(Map<FilterColumnKind, List<String>> byKind, Set<String> rest) {
        Map<FilterColumnKind, List<String>> operators = new EnumMap<>(FilterColumnKind.class);
        byKind.forEach((kind, names) -> operators.put(kind, List.copyOf(names)));
        Set<String> restOperators = Set.copyOf(rest);
        return new FilterCapabilities() {
            @Override
            public List<String> operators(FilterColumnKind kind) {
                return operators.getOrDefault(kind, List.of());
            }

            @Override
            public Set<String> restOperators() {
                return restOperators;
            }
        };
    }

    /** {@code operators} of {@code kind} in schema order, keeping only those in {@code wanted}. */
    static List<String> select(FilterColumnKind kind, Set<String> wanted) {
        return known(kind).stream().filter(wanted::contains).toList();
    }

    /** The engine's operator tables. */
    final class Known {
        private static final List<String> NULL_CHECKS = List.of(FILTER_IS_NULL, FILTER_IS_NOT_NULL);
        private static final List<String> FULL_TEXT = List.of(FILTER_SEARCH, FILTER_WEB_SEARCH, FILTER_PHRASE_SEARCH,
                FILTER_RAW_SEARCH);
        private static final List<String> STRING = List.of(FILTER_EQ, FILTER_NEQ, FILTER_LIKE, FILTER_ILIKE,
                FILTER_CONTAINS, FILTER_STARTS_WITH, FILTER_ENDS_WITH, FILTER_REGEX, FILTER_IREGEX, FILTER_IN,
                FILTER_NOT_IN, FILTER_IS_NULL, FILTER_IS_NOT_NULL);
        private static final List<String> ORDERED = List.of(FILTER_EQ, FILTER_NEQ, FILTER_GT, FILTER_GTE, FILTER_LT,
                FILTER_LTE, FILTER_IN, FILTER_NOT_IN, FILTER_IS_NULL, FILTER_IS_NOT_NULL);

        static final Map<FilterColumnKind, List<String>> GRAPHQL = graphql();

        static final Set<String> REST = Set.of(
                "eq", "neq", "gt", "gte", "lt", "lte",
                "like", "ilike", "match", "imatch",
                "in", "notin",
                "is", "isdistinct", "isnotnull",
                "cs", "cd", "ov", "sl", "sr", "nxl", "nxr", "adj",
                "fts", "plfts", "phfts", "wfts",
                "haskey",
                "jsoncontains", "contains", "jsoncontained", "containedin",
                "jsonpath", "jsonpathexists",
                "arraycontains", "arrayhasany", "arrayhasall", "arraylength",
                "startswith", "endswith",
                "vector");

        private Known() {
        }

        private static Map<FilterColumnKind, List<String>> graphql() {
            Map<FilterColumnKind, List<String>> tables = new EnumMap<>(FilterColumnKind.class);
            tables.put(FilterColumnKind.STRING, STRING);
            List<String> searchable = new ArrayList<>(STRING);
            searchable.addAll(FULL_TEXT);
            tables.put(FilterColumnKind.FULL_TEXT_STRING, List.copyOf(searchable));
            tables.put(FilterColumnKind.INT, ORDERED);
            tables.put(FilterColumnKind.FLOAT, ORDERED);
            tables.put(FilterColumnKind.DATE_TIME, ORDERED);
            tables.put(FilterColumnKind.BOOLEAN, List.of(FILTER_EQ, FILTER_NEQ, FILTER_IS_NULL, FILTER_IS_NOT_NULL));
            tables.put(FilterColumnKind.JSON, List.of(FILTER_EQ, FILTER_NEQ, FILTER_CONTAINS, FILTER_CONTAINED_BY,
                    FILTER_HAS_KEY, FILTER_HAS_KEYS, FILTER_HAS_ANY_KEYS, FILTER_IS_NULL, FILTER_IS_NOT_NULL));
            tables.put(FilterColumnKind.TSVECTOR, FULL_TEXT);
            tables.put(FilterColumnKind.ENUM, List.of(FILTER_EQ, FILTER_NEQ, FILTER_IN, FILTER_NOT_IN, FILTER_IS_NULL,
                    FILTER_IS_NOT_NULL));
            return Map.copyOf(tables);
        }

        private static Map<FilterColumnKind, List<String>> portable() {
            Map<FilterColumnKind, List<String>> tables = new EnumMap<>(GRAPHQL);
            List<String> text = STRING.stream()
                    .filter(name -> !FILTER_REGEX.equals(name) && !FILTER_IREGEX.equals(name)).toList();
            tables.put(FilterColumnKind.STRING, text);
            tables.put(FilterColumnKind.FULL_TEXT_STRING, text);
            tables.put(FilterColumnKind.JSON, NULL_CHECKS);
            tables.put(FilterColumnKind.TSVECTOR, List.of());
            return tables;
        }
    }
}
