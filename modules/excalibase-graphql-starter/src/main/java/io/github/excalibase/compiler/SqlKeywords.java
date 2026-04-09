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
    public static final String ORDER_BY = " ORDER BY ";
    public static final String IN = " IN ";
    public static final String IS_NULL = " IS NULL";
    public static final String IS_NOT_NULL = " IS NOT NULL";
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

    // --- Helper methods ---

    /** Join columns with comma separator: "col1, col2, col3" */
    public static String joinCols(List<String> columns) {
        return String.join(COMMA_SEP, columns);
    }

    /** Wrap in parentheses: "(content)" */
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
