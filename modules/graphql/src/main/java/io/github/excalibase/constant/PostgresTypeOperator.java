package io.github.excalibase.constant;

/**
 * Utility class for PostgreSQL type operations and checking.
 * Provides centralized logic for determining PostgreSQL data types.
 */
public class PostgresTypeOperator {
    
    private PostgresTypeOperator() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Checks if the given type is an integer type (excluding interval)
     * @param type the database type to check
     * @return true if it's an integer type
     */
    public static boolean isIntegerType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return (lowerType.contains(ColumnTypeConstant.INT) || lowerType.contains(ColumnTypeConstant.SERIAL)) 
               && !lowerType.equals(ColumnTypeConstant.INTERVAL);
    }
    
    /**
     * Checks if the given type is a floating-point numeric type
     * @param type the database type to check
     * @return true if it's a floating-point type
     */
    public static boolean isFloatingPointType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return lowerType.contains(ColumnTypeConstant.NUMERIC) || lowerType.contains(ColumnTypeConstant.DECIMAL) ||
               lowerType.contains(ColumnTypeConstant.REAL) || lowerType.contains(ColumnTypeConstant.DOUBLE_PRECISION) ||
               lowerType.contains(ColumnTypeConstant.FLOAT) || 
               lowerType.contains(ColumnTypeConstant.DOUBLE);
    }
    
    /**
     * Checks if the given type is a boolean type
     * @param type the database type to check
     * @return true if it's a boolean type
     */
    public static boolean isBooleanType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return lowerType.contains(ColumnTypeConstant.BOOLEAN) || lowerType.contains(ColumnTypeConstant.BOOL);
    }
    
    /**
     * Checks if the given type is a JSON type
     * @param type the database type to check
     * @return true if it's a JSON type
     */
    public static boolean isJsonType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return lowerType.contains(ColumnTypeConstant.JSON) || lowerType.contains(ColumnTypeConstant.JSONB);
    }
    
    /**
     * Checks if the given type is a date/time type
     * @param type the database type to check
     * @return true if it's a date/time type
     */
    public static boolean isDateTimeType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return lowerType.contains(ColumnTypeConstant.TIMESTAMP) || lowerType.contains(ColumnTypeConstant.TIMESTAMPTZ) ||
               lowerType.contains(ColumnTypeConstant.DATE) || lowerType.contains(ColumnTypeConstant.TIME) ||
               lowerType.contains(ColumnTypeConstant.TIMETZ) || lowerType.contains(ColumnTypeConstant.INTERVAL);
    }
    
    /**
     * Checks if the given type is a UUID type
     * @param type the database type to check
     * @return true if it's a UUID type
     */
    public static boolean isUuidType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return lowerType.contains(ColumnTypeConstant.UUID);
    }
    
    /**
     * Checks if the given type is a binary/network type
     * @param type the database type to check
     * @return true if it's a binary or network type
     */
    public static boolean isBinaryOrNetworkType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return lowerType.contains(ColumnTypeConstant.BYTEA) || lowerType.contains(ColumnTypeConstant.INET) ||
               lowerType.contains(ColumnTypeConstant.CIDR) || lowerType.contains(ColumnTypeConstant.MACADDR) ||
               lowerType.contains(ColumnTypeConstant.MACADDR8);
    }
    
    /**
     * Checks if the given type is a bit string type
     * @param type the database type to check
     * @return true if it's a bit string type
     */
    public static boolean isBitType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return lowerType.contains(ColumnTypeConstant.BIT) || lowerType.contains(ColumnTypeConstant.VARBIT);
    }
    
    /**
     * Checks if the given type is an XML type
     * @param type the database type to check
     * @return true if it's an XML type
     */
    public static boolean isXmlType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return lowerType.contains(ColumnTypeConstant.XML);
    }
    
    /**
     * Checks if the given type is an array type
     * @param type the database type to check
     * @return true if it's an array type
     */
    public static boolean isArrayType(String type) {
        if (type == null) return false;
        return type.contains(ColumnTypeConstant.ARRAY_SUFFIX);
    }
} 