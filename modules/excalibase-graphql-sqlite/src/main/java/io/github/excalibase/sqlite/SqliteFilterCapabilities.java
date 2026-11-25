package io.github.excalibase.sqlite;

import io.github.excalibase.FilterCapabilities;
import io.github.excalibase.FilterColumnKind;

import java.util.List;
import java.util.Set;

import static io.github.excalibase.schema.GraphqlConstants.FILTER_IREGEX;
import static io.github.excalibase.schema.GraphqlConstants.FILTER_REGEX;

/**
 * What SQLite runs: comparisons, lists, LIKE (and ILIKE by lower-casing both sides), starts/ends/contains and null
 * checks. It has no regular expressions, full-text search in a plain column, array types, or JSON operators, so none
 * is offered and a request for one is refused by name.
 */
final class SqliteFilterCapabilities implements FilterCapabilities {

    private static final FilterCapabilities TABLE = FilterCapabilities.PORTABLE;

    @Override
    public List<String> operators(FilterColumnKind kind) {
        return TABLE.operators(kind);
    }

    @Override
    public Set<String> restOperators() {
        return TABLE.restOperators();
    }

    @Override
    public String unsupportedReason(FilterColumnKind kind, String operator) {
        if (FILTER_REGEX.equals(operator) || FILTER_IREGEX.equals(operator)) {
            return "SQLite has no regular expression operator; use like, ilike, startsWith, endsWith or contains";
        }
        if (kind == FilterColumnKind.JSON) {
            return "SQLite offers only null checks on a JSON column";
        }
        return "";
    }
}
