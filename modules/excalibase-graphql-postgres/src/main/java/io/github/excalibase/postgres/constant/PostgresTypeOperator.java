package io.github.excalibase.postgres.constant;

import io.github.excalibase.constant.ColumnTypeConstant;

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
        return (lowerType.equals(ColumnTypeConstant.INT) || lowerType.equals("int2") || 
                lowerType.equals("int4") || lowerType.equals("int8") ||
                lowerType.equals("integer") || lowerType.equals(ColumnTypeConstant.BIGINT) || 
                lowerType.equals(ColumnTypeConstant.SMALLINT) || lowerType.equals(ColumnTypeConstant.SERIAL) ||
                lowerType.equals("serial2") || lowerType.equals("serial4") ||
                lowerType.equals("serial8") || lowerType.equals("smallserial") ||
                lowerType.equals(ColumnTypeConstant.BIGSERIAL));
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
     * Checks if the given type is a JSON type (JSON or JSONB)
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
     * Checks if the given type is a network type (INET, CIDR, etc.)
     * @param type the database type to check
     * @return true if it's a network type
     */
    public static boolean isNetworkType(String type) {
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


    /**
     * Gets the base type from an array type
     * @param arrayType the array type (e.g., "integer[]")
     * @return the base type (e.g., "integer")
     */
    public static String getBaseArrayType(String arrayType) {
        if (arrayType == null || !isArrayType(arrayType)) {
            return arrayType;
        }
        return arrayType.replace(ColumnTypeConstant.ARRAY_SUFFIX, "");
    }
    
    /**
     * Checks if the given type is a built-in PostgreSQL type
     * @param type the database type to check
     * @return true if it's a built-in type
     */
    public static boolean isBuiltInType(String type) {
        if (type == null) return false;
        
        // For array types, check if the base type is built-in
        if (isArrayType(type)) {
            String baseType = getBaseArrayType(type);
            return isBuiltInType(baseType);
        }
        
        return isIntegerType(type) || isFloatingPointType(type) || isBooleanType(type) ||
               isJsonType(type) || isDateTimeType(type) || isUuidType(type) ||
               isNetworkType(type) || isBitType(type) || isXmlType(type) ||
               isTextType(type);
    }
    
    /**
     * Checks if the given type is a text type
     * @param type the database type to check
     * @return true if it's a text type
     */
    public static boolean isTextType(String type) {
        if (type == null) return false;
        String lowerType = type.toLowerCase();
        return lowerType.contains(ColumnTypeConstant.VARCHAR) || lowerType.contains(ColumnTypeConstant.TEXT) ||
               lowerType.contains(ColumnTypeConstant.CHAR);
    }
    
    /**
     * Gets the category of a PostgreSQL type
     * @param type the database type to categorize
     * @return the type category
     */
    public static String getTypeCategory(String type) {
        if (type == null) return "unknown";
        
        // Check array type first since it takes precedence
        if (isArrayType(type)) return "array";
        
        if (isIntegerType(type)) return "integer";
        if (isFloatingPointType(type)) return "numeric";
        if (isBooleanType(type)) return "boolean";
        if (isTextType(type)) return "text";
        if (isDateTimeType(type)) return "datetime";
        if (isJsonType(type)) return "json";
        if (isUuidType(type)) return "uuid";
        if (isNetworkType(type)) return "network";
        if (isBitType(type)) return "bit";
        if (isXmlType(type)) return "xml";
        
        return "unknown";
    }
} 