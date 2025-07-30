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
     * Checks if the given type is a custom enum type
     * Note: This is a heuristic check - may need to be enhanced with actual enum type lookup
     * @param type the database type to check
     * @return true if it appears to be a custom enum type
     */
    public static boolean isCustomEnumType(String type) {
        if (type == null || type.trim().isEmpty()) return false;
        String lowerType = type.toLowerCase();
        
        // If it's a known built-in type, it's not a custom enum
        if (isIntegerType(type) || isFloatingPointType(type) || isBooleanType(type) ||
            isJsonType(type) || isDateTimeType(type) || isUuidType(type) ||
            isNetworkType(type) || isBitType(type) || isXmlType(type) ||
            lowerType.contains(ColumnTypeConstant.VARCHAR) || lowerType.contains(ColumnTypeConstant.TEXT) ||
            lowerType.contains(ColumnTypeConstant.CHAR)) {
            return false;
        }
        
        // If it's an array, check the base type
        if (isArrayType(type)) {
            String baseType = type.replace(ColumnTypeConstant.ARRAY_SUFFIX, "");
            return isCustomEnumType(baseType);
        }
        
        // If it doesn't match any built-in types, it might be custom
        return true;
    }

    /**
     * Checks if the given type is a custom composite type
     * Note: This is a heuristic check - may need to be enhanced with actual composite type lookup
     * @param type the database type to check
     * @return true if it appears to be a custom composite type
     */
    public static boolean isCustomCompositeType(String type) {
        if (type == null || type.trim().isEmpty()) return false;
        String lowerType = type.toLowerCase();
        
        // If it's a known built-in type, it's not a custom composite
        if (isIntegerType(type) || isFloatingPointType(type) || isBooleanType(type) ||
            isJsonType(type) || isDateTimeType(type) || isUuidType(type) ||
            isNetworkType(type) || isBitType(type) || isXmlType(type) ||
            lowerType.contains(ColumnTypeConstant.VARCHAR) || lowerType.contains(ColumnTypeConstant.TEXT) ||
            lowerType.contains(ColumnTypeConstant.CHAR)) {
            return false;
        }
        
        // If it's an array, check the base type
        if (isArrayType(type)) {
            String baseType = type.replace(ColumnTypeConstant.ARRAY_SUFFIX, "");
            return isCustomCompositeType(baseType);
        }
        
        // If it doesn't match any built-in types, it might be custom
        return true;
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
} 