package io.github.excalibase.utils;

import java.sql.Types;
import java.util.List;
import java.util.Map;

/**
 * Static SQL utilities: named-parameter resolution and JDBC type mapping.
 */
public class SqlUtils {

    private SqlUtils() {}

    public record ResolvedSql(String sql, List<Object> values) {}

    public static ResolvedSql resolveNamedParams(String sql, Map<String, Object> params) {
        return new SqlParamParser(sql, params).parse();
    }

    public static int sqlTypeFor(String pgType) {
        if (pgType == null) return Types.OTHER;
        String type = pgType.toLowerCase();
        if (type.contains("int") || type.equals("bigint")) return Types.BIGINT;
        if (type.contains("numeric") || type.contains("decimal")) return Types.NUMERIC;
        if (type.contains("float") || type.contains("double") || type.equals("real")) return Types.DOUBLE;
        if (type.equals("boolean") || type.equals("bool")) return Types.BOOLEAN;
        if (type.equals("text") || type.startsWith("varchar") || type.startsWith("character")) return Types.VARCHAR;
        return Types.OTHER;
    }
}
