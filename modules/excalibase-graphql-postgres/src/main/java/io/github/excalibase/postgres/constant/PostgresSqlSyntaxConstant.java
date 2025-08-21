package io.github.excalibase.postgres.constant;

/**
 * SQL syntax constants for query building.
 */
public class PostgresSqlSyntaxConstant {
    private PostgresSqlSyntaxConstant() {
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


    // SQL casting and parameter operators
    public static final String CAST_OPERATOR = "::";
    public static final String EQUALS_PARAM = " = :";
    public static final String NOT_EQUALS_PARAM = " != :";
    public static final String GREATER_THAN = " > :";
    public static final String GREATER_THAN_EQUALS = " >= :";
    public static final String LESS_THAN = " < :";
    public static final String LESS_THAN_EQUALS = " <= :";
    
    // SQL NULL checks
    public static final String IS_NULL = " IS NULL";
    public static final String IS_NOT_NULL = " IS NOT NULL";
    
    // SQL text casting for special types
    public static final String CAST_TO_TEXT = "::text";
    
    // Order direction
    public static final String ASC_ORDER = "ASC";
    public static final String DESC_ORDER = "DESC";
}
