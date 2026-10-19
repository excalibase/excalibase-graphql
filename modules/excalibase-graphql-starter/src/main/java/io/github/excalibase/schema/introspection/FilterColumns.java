package io.github.excalibase.schema.introspection;

import io.github.excalibase.ArrayColumns;
import io.github.excalibase.FilterColumnKind;
import io.github.excalibase.schema.SchemaInfo;

/**
 * The filter kind of a column. The schema's filter inputs and the compiler's operator check both read it here, so
 * what is advertised and what is accepted cannot drift apart.
 */
public final class FilterColumns {

    private FilterColumns() {
    }

    public static FilterColumnKind kindOf(SchemaInfo schemaInfo, String table, String column) {
        String enumType = schemaInfo.getEnumType(table, column);
        if (enumType != null && schemaInfo.getEnumTypes().containsKey(enumType)) return FilterColumnKind.ENUM;
        String type = schemaInfo.getColumnType(table, column);
        if (ArrayColumns.isArray(type)) return FilterColumnKind.ARRAY;
        if (TypeMapping.isJsonType(type)) return FilterColumnKind.JSON;
        if (TypeMapping.isBooleanType(type)) return FilterColumnKind.BOOLEAN;
        if (TypeMapping.isDateTimeType(type)) return FilterColumnKind.DATE_TIME;
        if (TypeMapping.isFloatType(type)) return FilterColumnKind.FLOAT;
        if (TypeMapping.isIntegerType(type)) return FilterColumnKind.INT;
        if ("tsvector".equalsIgnoreCase(type)) return FilterColumnKind.TSVECTOR;
        return schemaInfo.isFullTextColumn(table, column) ? FilterColumnKind.FULL_TEXT_STRING : FilterColumnKind.STRING;
    }
}
