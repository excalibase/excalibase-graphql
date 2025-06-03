package io.github.excalibase.constant;

/**
 * SQL syntax constants for query building.
 */
public class SQLSyntax {
    private SQLSyntax() {
    }

    public static final String FROM_WITH_SPACE = " FROM ";
    public static final String WHERE_WITH_SPACE = " WHERE ";
    public static final String AND_WITH_SPACE = " AND ";
    public static final String SELECT_WITH_SPACE = "SELECT ";
    public static final String DELETE_WITH_SPACE = "DELETE FROM ";
    public static final String ORDER_BY_WITH_SPACE = " ORDER BY ";

    public static final String IN_WITH_SPACE = " IN ";
    public static final String LIMIT_WITH_SPACE = " LIMIT ";
    public static final String OFFSET_WITH_SPACE = " OFFSET ";

    public static final String SELECT_COUNT_FROM_WITH_SPACE = "SELECT COUNT(*) FROM ";
}
