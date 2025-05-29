package io.github.excalibase.constant;

public class SQLSyntax {
    private SQLSyntax() {
    }

    public static final String FROM_WITH_SPACE = " FROM ";
    public static final String WHERE_WITH_SPACE = " WHERE ";
    public static final String AND_WITH_SPACE = " AND ";
    public static final String SELECT_WITH_SPACE = "SELECT ";
    public static final String ORDER_BY_WITH_SPACE = " ORDER BY ";

    public static final String IN_WITH_PARAM = " IN %s";
    public static final String LIMIT_WITH_PARAM = " LIMIT %s";
    public static final String OFFSET_WITH_PARAM = " OFFSET %s";

    public static final String SELECT_COUNT_FROM_WITH_SPACE = "SELECT COUNT(*) FROM ";
}
