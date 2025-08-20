package io.github.excalibase.postgres.constant;

/**
 * SQL syntax constants for query building.
 */
public class PostgresColumnTypeConstant {
    private PostgresColumnTypeConstant() {
    }

    // SQL keywords with spaces
    public static final String FROM_WITH_SPACE = " FROM ";
    public static final String WHERE_WITH_SPACE = " WHERE ";
    public static final String AND_WITH_SPACE = " AND ";
    public static final String SELECT_WITH_SPACE = "SELECT ";
    public static final String DELETE_WITH_SPACE = "DELETE FROM ";
    public static final String ORDER_BY_WITH_SPACE = " ORDER BY ";
    public static final String INSERT_INTO_WITH_SPACE = "INSERT INTO ";
    public static final String UPDATE_WITH_SPACE = "UPDATE ";
    public static final String SET_WITH_SPACE = " SET ";
    public static final String RETURNING_WITH_SPACE = " RETURNING ";

    // SQL operators and clauses
    public static final String IN_WITH_SPACE = " IN ";
    public static final String LIMIT_WITH_SPACE = " LIMIT ";
    public static final String OFFSET_WITH_SPACE = " OFFSET ";

    // SQL aggregate functions
    public static final String SELECT_COUNT_FROM_WITH_SPACE = "SELECT COUNT(*) FROM ";

    // SQL wildcard for RETURNING clause
    public static final String RETURNING_ALL = " RETURNING *";

    public static final String POSTGRES_ENUM = "postgres_enum";
    public static final String POSTGRES_COMPOSITE = "postgres_composite";
}
