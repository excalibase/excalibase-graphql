package io.github.excalibase.mysql;

import io.github.excalibase.FilterCapabilities;
import io.github.excalibase.FilterColumnKind;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.excalibase.schema.GraphqlConstants.FILTER_EQ;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_NEQ;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_PHRASE_SEARCH;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_RAW_SEARCH;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_SEARCH;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_WEB_SEARCH;

/**
 * What MySQL runs natively: regex through REGEXP_LIKE, JSON containment and keys through JSON_CONTAINS and
 * JSON_CONTAINS_PATH, and full-text search through MATCH ... AGAINST on a column with its own FULLTEXT index. It
 * has no tsvector, no web-search syntax, and no JSON equality on a bound value.
 */
final class MysqlFilterCapabilities implements FilterCapabilities {

    private static final Set<String> FULL_TEXT = Set.of(FILTER_SEARCH, FILTER_PHRASE_SEARCH, FILTER_RAW_SEARCH);
    private static final Set<String> REST = Set.of("eq", "neq", "gt", "gte", "lt", "lte", "like", "ilike", "match",
            "imatch", "in", "notin", "is", "isnotnull", "isdistinct", "startswith", "endswith", "fts", "plfts", "phfts",
            "haskey", "jsoncontains", "contains", "cs", "jsoncontained", "containedin", "cd");

    private final FilterCapabilities table = FilterCapabilities.of(operators(), REST);

    private static Map<FilterColumnKind, List<String>> operators() {
        Map<FilterColumnKind, List<String>> operators = new EnumMap<>(FilterColumnKind.class);
        for (FilterColumnKind kind : FilterColumnKind.values()) {
            operators.put(kind, FilterCapabilities.known(kind));
        }
        List<String> searchable = new ArrayList<>(FilterCapabilities.known(FilterColumnKind.STRING));
        searchable.addAll(FilterCapabilities.select(FilterColumnKind.FULL_TEXT_STRING, FULL_TEXT));
        operators.put(FilterColumnKind.FULL_TEXT_STRING, searchable);
        operators.put(FilterColumnKind.JSON, FilterCapabilities.known(FilterColumnKind.JSON).stream()
                .filter(name -> !FILTER_EQ.equals(name) && !FILTER_NEQ.equals(name)).toList());
        operators.put(FilterColumnKind.TSVECTOR, List.of());
        operators.put(FilterColumnKind.ARRAY, List.of());
        return operators;
    }

    @Override
    public List<String> operators(FilterColumnKind kind) {
        return table.operators(kind);
    }

    @Override
    public Set<String> restOperators() {
        return table.restOperators();
    }

    @Override
    public String unsupportedReason(FilterColumnKind kind, String operator) {
        if (FILTER_WEB_SEARCH.equals(operator)) {
            return "MySQL has no web-search syntax; rawSearch takes MySQL boolean-mode syntax";
        }
        if (FULL_TEXT.contains(operator)) {
            return "full-text search on MySQL needs a FULLTEXT index on this column alone";
        }
        if (kind == FilterColumnKind.JSON && (FILTER_EQ.equals(operator) || FILTER_NEQ.equals(operator))) {
            return "compare JSON on MySQL with contains and containedBy";
        }
        return "";
    }
}
