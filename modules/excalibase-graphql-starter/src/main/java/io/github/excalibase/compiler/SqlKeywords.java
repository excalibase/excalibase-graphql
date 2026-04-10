package io.github.excalibase.compiler;

import java.util.List;

/**
 * SQL keyword constants and helper methods for building SQL fragments.
 * Eliminates hardcoded SQL strings across compilers and query builders.
 */
public final class SqlKeywords {

    private SqlKeywords() {}

    // --- SQL keywords (with appropriate spacing) ---
    public static final String SELECT = "SELECT ";
    public static final String FROM = " FROM ";
    public static final String WHERE = " WHERE ";
    public static final String AND = " AND ";
    public static final String OR = " OR ";
    public static final String SET = " SET ";
    public static final String VALUES = " VALUES ";
    public static final String INSERT_INTO = "INSERT INTO ";
    public static final String UPDATE = "UPDATE ";
    public static final String DELETE_FROM = "DELETE FROM ";
    public static final String WITH = "WITH ";
    public static final String AS_OPEN = " AS (";
    public static final String RETURNING_ALL = " RETURNING *)";
    public static final String LIMIT = " LIMIT ";
    public static final String OFFSET = " OFFSET ";
    public static final String ORDER_BY = " ORDER BY ";
    public static final String IN = " IN ";
    public static final String IS_NULL = " IS NULL";
    public static final String IS_NOT_NULL = " IS NOT NULL";
    public static final String IS_TRUE = " IS TRUE";
    public static final String IS_FALSE = " IS FALSE";
    public static final String NOT_IN = " NOT IN ";
    public static final String NEQ = " <> ";
    public static final String GT = " > ";
    public static final String GTE = " >= ";
    public static final String LT = " < ";
    public static final String LTE = " <= ";
    public static final String MATCH_TSQUERY = " @@ ";
    public static final String FN_TO_TSVECTOR = "to_tsvector";
    public static final String FN_TO_TSQUERY = "to_tsquery";
    public static final String FN_PLAINTO_TSQUERY = "plainto_tsquery";
    public static final String FN_PHRASETO_TSQUERY = "phraseto_tsquery";
    public static final String FN_WEBSEARCH_TSQUERY = "websearch_to_tsquery";
    public static final String FN_JSONB_EXISTS = "jsonb_exists";
    public static final String FN_ARRAY_LENGTH = "array_length";
    public static final String REGEX_MATCH = " ~ ";
    public static final String REGEX_IMATCH = " ~* ";
    public static final String IS_DISTINCT_FROM = " IS DISTINCT FROM ";
    public static final String CONTAINS = " @> ";
    public static final String CONTAINED_BY = " <@ ";
    public static final String OVERLAPS = " && ";
    public static final String JSONPATH_EXISTS = " @? ";
    public static final String ADJACENT = " -|- ";
    public static final String CAST_JSONB = "::jsonb";
    public static final String CAST_JSONPATH = "::jsonpath";
    public static final String ARRAY_PREFIX = "ARRAY[";
    public static final String ON_CONFLICT = " ON CONFLICT ";
    public static final String DO_NOTHING = " DO NOTHING";
    public static final String DO_UPDATE = " DO UPDATE";
    public static final String EXCLUDED = "EXCLUDED";
    public static final String COALESCE = "COALESCE";
    public static final String FN_JSON_AGG = "json_agg";
    public static final String EMPTY_JSON_ARRAY = "'[]'::json";
    public static final String STAR = "*";
    public static final String DOT_STAR = ".*";
    public static final String SPACE = " ";
    public static final String DOT = ".";
    public static final String UNDERSCORE = "_";
    public static final String P_OR = "or_";
    public static final String ALIAS_R = "r";
    public static final String LIKE = " LIKE ";
    public static final String NOT = "NOT ";
    public static final String COUNT_ALL = "count(*)";
    public static final String CTID = "ctid";
    public static final String ASC = "ASC";
    public static final String DESC = "DESC";
    public static final String INNER_ALIAS = "__inner";

    // --- Separators ---
    public static final String COMMA_SEP = ", ";
    public static final String PARAM_PREFIX = ":";
    public static final String ASSIGN = " = ";

    // --- Named parameter prefixes ---
    public static final String P_INSERT = "ins_";
    public static final String P_BULK_INSERT = "bins_";
    public static final String P_UPDATE = "upd_";
    public static final String P_UC_SET = "uc_set_";
    public static final String P_UC_AT_MOST = "uc_atmost_";
    public static final String P_DC_AT_MOST = "dc_atmost_";
    public static final String P_LAST_ID = "last_id_";
    public static final String P_AFTER = "p_after_";
    public static final String P_BEFORE = "p_before_";
    public static final String P_LIMIT = "limit_";
    public static final String P_HN_LIMIT = "hn_limit_";
    public static final String P_PAGE_LIMIT = "page_limit_";
    public static final String P_FILTER = "f_";
    public static final String P_FILTER_COUNT = "fc_";
    public static final String P_WHERE_FILTER = "wf_";
    public static final String P_DELETE_FILTER = "df_";
    public static final String AS_BODY = " AS body";
    public static final String AS_TOTAL_COUNT = " AS total_count";

    // --- Helper methods ---

    /** Join columns with comma separator: "col1, col2, col3" */
    public static String joinCols(List<String> columns) {
        return String.join(COMMA_SEP, columns);
    }

    /** Wrap in parentheses: "(content)" */
    public static final String STRICTLY_LEFT = " << ";
    public static final String STRICTLY_RIGHT = " >> ";
    public static final String NO_EXTEND_LEFT = " &< ";
    public static final String NO_EXTEND_RIGHT = " &> ";

    public static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    public static String parens(String content) {
        return "(" + content + ")";
    }

    /** Build parameter reference: ":paramName" */
    public static String param(String paramName) {
        return PARAM_PREFIX + paramName;
    }

    /** Build assignment: "col = :paramName" */
    public static String assign(String quotedCol, String paramName) {
        return quotedCol + ASSIGN + PARAM_PREFIX + paramName;
    }

    /** Build assignment with cast: "col = :paramName::type" */
    public static String assignWithCast(String quotedCol, String paramName, String cast) {
        return quotedCol + ASSIGN + PARAM_PREFIX + paramName + cast;
    }

    /** Build WHERE ... IN (SELECT ctid ...) for collection mutations */
    public static String ctidSubquery(String table, String innerAlias, String filterWhere, String limitParam) {
        return WHERE + CTID + IN + parens(
                SELECT + CTID + FROM + table + " " + innerAlias + filterWhere + LIMIT + PARAM_PREFIX + limitParam
        );
    }

    /** Build condition: "alias.col = other.col" */
    public static String joinCondition(String leftAlias, String leftCol, String rightAlias, String rightCol) {
        return leftAlias + "." + leftCol + ASSIGN + rightAlias + "." + rightCol;
    }

    /** Generate a unique named parameter: prefix + key + "_" + counter */
    public static String namedParam(String prefix, String key, int counter) {
        return prefix + key + "_" + counter;
    }

    /** Generate a unique named parameter: prefix + counter */
    public static String namedParam(String prefix, int counter) {
        return prefix + counter;
    }
}
