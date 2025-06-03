package io.github.excalibase.constant;

/**
 * Database column type constants for GraphQL type mapping.
 */
public class ColumnTypeConstant {
    private ColumnTypeConstant() {
    }

    // UUID and identifier types
    public static final String UUID = "uuid";

    // Integer types
    public static final String INT = "int";
    public static final String BIGINT = "bigint";
    public static final String SMALLINT = "smallint";
    public static final String SERIAL = "serial";
    public static final String BIGSERIAL = "bigserial";

    // Decimal and floating-point types
    public static final String DECIMAL = "decimal";
    public static final String NUMERIC = "numeric";
    public static final String DOUBLE = "double";
    public static final String FLOAT = "float";
    public static final String REAL = "real";
    public static final String DOUBLE_PRECISION = "double precision";

    // Boolean types
    
    /** Boolean column type (short form) */
    public static final String BOOL = "bool";
    
    /** Boolean column type (full form) */
    public static final String BOOLEAN = "boolean";

    // Date and time types

    /** Timestamp column type */
    public static final String TIMESTAMP = "timestamp";
    
    /** Date column type */
    public static final String DATE = "date";
    
    /** Time column type */
    public static final String TIME = "time";
} 