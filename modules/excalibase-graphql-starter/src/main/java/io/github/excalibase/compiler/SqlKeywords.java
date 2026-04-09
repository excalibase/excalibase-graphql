package io.github.excalibase.compiler;

/**
 * SQL keyword constants. Eliminates hardcoded SQL strings across compilers.
 */
public final class SqlKeywords {

    private SqlKeywords() {}

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
    public static final String AS = " AS (";
    public static final String RETURNING_ALL = " RETURNING *)";
    public static final String LIMIT = " LIMIT ";
    public static final String ORDER_BY = " ORDER BY ";
    public static final String INNER_JOIN = " JOIN ";
    public static final String LEFT_JOIN = " LEFT JOIN ";
    public static final String ON = " ON ";
    public static final String IN = " IN ";
    public static final String NOT = "NOT ";
    public static final String IS_NULL = " IS NULL";
    public static final String IS_NOT_NULL = " IS NOT NULL";
    public static final String LIKE = " LIKE ";
    public static final String COUNT_ALL = "count(*)";
    public static final String CTID = "ctid";
    public static final String ASC = "ASC";
    public static final String DESC = "DESC";
}
