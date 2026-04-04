package io.github.excalibase;

import graphql.language.*;

import java.util.*;

/**
 * Mutation router and shared helpers. Delegates to a MutationCompiler
 * implementation for dialect-specific mutation compilation.
 */
public class MutationBuilder {

    private final SchemaInfo schemaInfo;
    private final SqlDialect dialect;
    private final FilterBuilder filterBuilder;
    private final String dbSchema;
    private final QueryBuilder queryBuilder;

    private final MutationCompiler mutationCompiler;

    public MutationBuilder(SchemaInfo schemaInfo, SqlDialect dialect, FilterBuilder filterBuilder,
                           String dbSchema, QueryBuilder queryBuilder, MutationCompiler mutationCompiler) {
        this.schemaInfo = schemaInfo;
        this.dialect = dialect;
        this.filterBuilder = filterBuilder;
        this.dbSchema = dbSchema;
        this.queryBuilder = queryBuilder;
        this.mutationCompiler = mutationCompiler;
    }

    // === Accessors for dialect-specific compilers ===
    public SchemaInfo schemaInfo() { return schemaInfo; }
    public SqlDialect dialect() { return dialect; }
    public FilterBuilder filterBuilder() { return filterBuilder; }
    public String dbSchema() { return dbSchema; }

    /** Resolve the qualified table expression using per-table schema metadata. */
    public String qualifiedTable(String tableName) {
        String schema = schemaInfo.resolveSchema(tableName, dbSchema);
        String rawTable = tableName.contains(".") ? tableName.substring(tableName.indexOf('.') + 1) : tableName;
        return dialect.qualifiedTable(schema, rawTable);
    }
    public QueryBuilder queryBuilder() { return queryBuilder; }

    // === Mutation compilation — delegates to dialect-specific compiler ===

    public static final String MUTATION_DELETE = "__DELETE__";

    public record MysqlMutationResult(String dmlSql, String selectSql, String lastInsertIdParam) {}

    /**
     * Compile a mutation field into a CompiledQuery.
     * Delegates to the MutationCompiler implementation.
     */
    public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName,
                                                      Map<String, Object> params, Map<String, Object> variables) {
        return mutationCompiler.compileMutation(field, fieldName, params, variables, this);
    }

    // === Stored procedure support ===

    public SqlCompiler.ProcedureCallInfo buildProcedureCallInfo(Field field, String procName,
                                                                  Map<String, Object> params, Map<String, Object> variables) {
        SchemaInfo.ProcedureInfo proc = schemaInfo.getStoredProcedures().get(procName);
        if (proc == null) return null;

        String rawProc = procName.contains(".") ? procName.substring(procName.indexOf('.') + 1) : procName;
        String procSchema = procName.contains(".") ? procName.substring(0, procName.indexOf('.')) : dbSchema;
        String qualifiedName = procSchema + "." + dialect.quoteIdentifier(rawProc);
        List<SqlCompiler.ProcedureCallParam> allParams = new ArrayList<>();

        for (SchemaInfo.ProcParam p : proc.params()) {
            Object value = null;
            if ("IN".equals(p.mode()) || "INOUT".equals(p.mode())) {
                Argument arg = findArg(field, p.name());
                if (arg != null) {
                    value = extractValue(arg.getValue(), variables);
                }
            }
            allParams.add(new SqlCompiler.ProcedureCallParam(p.name(), p.mode(), p.type(), value));
        }

        return new SqlCompiler.ProcedureCallInfo(qualifiedName, allParams);
    }

    public String resolveStoredProcedure(String pascalName) {
        String snake = NamingUtils.camelToSnakeCase(pascalName);
        if (schemaInfo.getStoredProcedures().containsKey(snake)) return snake;
        String lower = pascalName.toLowerCase();
        if (schemaInfo.getStoredProcedures().containsKey(lower)) return lower;

        // Compound keys: match prefixed name (e.g., "HanaTransferFunds" → "hana.transfer_funds")
        for (String procKey : schemaInfo.getStoredProcedures().keySet()) {
            if (!procKey.contains(".")) continue;
            String schema = procKey.substring(0, procKey.indexOf('.'));
            String rawProc = procKey.substring(procKey.indexOf('.') + 1);
            if (NamingUtils.schemaTypeName(schema, rawProc).equals(pascalName)) return procKey;
        }
        return null;
    }

    // === Table name resolution ===

    public String resolveMutationTable(String typeName) {
        String snake = NamingUtils.camelToSnakeCase(typeName);
        if (schemaInfo.hasTable(snake)) return snake;
        String lower = typeName.toLowerCase();
        if (schemaInfo.hasTable(lower)) return lower;

        // Compound keys: match prefixed type name (e.g., "TestSchemaCustomer" → "test_schema.customer")
        for (String table : schemaInfo.getTableNames()) {
            if (!table.contains(".")) continue;
            String schema = table.substring(0, table.indexOf('.'));
            String rawTable = table.substring(table.indexOf('.') + 1);
            if (NamingUtils.schemaTypeName(schema, rawTable).equals(typeName)) return table;
        }
        return null;
    }

    // === Shared helpers (public, used by dialect-specific mutation compilers) ===

    public String getEnumCastForMutation(String tableName, String colName) {
        String enumType = schemaInfo.getEnumType(tableName, colName);
        String resolvedSchema = schemaInfo.resolveSchema(tableName, dbSchema);
        if (enumType != null) {
            // Extract raw enum name from compound key (e.g., "hana.priority_level" → "priority_level")
            String rawEnum = enumType.contains(".") ? enumType.substring(enumType.indexOf('.') + 1) : enumType;
            if (schemaInfo.getEnumTypes().containsKey(enumType)) {
                return dialect.enumCast(resolvedSchema, rawEnum);
            }
            if (schemaInfo.isCompositeType(enumType)) {
                return dialect.enumCast(resolvedSchema, rawEnum);
            }
        }
        String colType = schemaInfo.getColumnType(tableName, colName);
        if (colType != null) {
            String cast = dialect.paramCast(colType);
            if (!cast.isEmpty()) return cast;
        }
        return "";
    }

    public Object convertCompositeValue(String tableName, String colName, Object value) {
        if (value == null) return null;
        String udtName = schemaInfo.getEnumType(tableName, colName);
        if (udtName == null || !schemaInfo.isCompositeType(udtName)) return value;

        List<SchemaInfo.CompositeTypeField> fields = schemaInfo.getCompositeTypes().get(udtName);
        if (fields == null) return value;

        if (value instanceof Map<?, ?> map) {
            return buildTupleString(fields, map);
        }
        if (value instanceof String s && s.startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class);
                return buildTupleString(fields, parsed);
            } catch (Exception e) {
                return value;
            }
        }
        return value;
    }

    private String buildTupleString(List<SchemaInfo.CompositeTypeField> fields, Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(",");
            Object val = map.get(fields.get(i).name());
            if (val != null) sb.append(val);
        }
        sb.append(")");
        return sb.toString();
    }

    // === Argument/value extraction ===

    public Argument findArg(Field field, String name) {
        return field.getArguments().stream()
                .filter(a -> name.equals(a.getName()))
                .findFirst().orElse(null);
    }

    public Map<String, Object> extractObjectFields(Value<?> value, Map<String, Object> variables) {
        if (value instanceof ObjectValue ov) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (ObjectField of : ov.getObjectFields()) {
                result.put(of.getName(), extractValue(of.getValue()));
            }
            return result;
        }
        if (value instanceof VariableReference vr && variables != null) {
            Object resolved = variables.get(vr.getName());
            if (resolved instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((k, v) -> result.put(k.toString(), v));
                return result;
            }
        }
        return Map.of();
    }

    public List<Map<String, Object>> extractArrayOfObjects(Value<?> value, Map<String, Object> variables) {
        if (value instanceof ArrayValue av) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Value<?> v : av.getValues()) {
                result.add(extractObjectFields(v, variables));
            }
            return result;
        }
        if (value instanceof VariableReference vr && variables != null) {
            Object resolved = variables.get(vr.getName());
            if (resolved instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        map.forEach((k, v) -> row.put(k.toString(), v));
                        result.add(row);
                    }
                }
                return result;
            }
        }
        return List.of();
    }

    public static Object extractValue(Value<?> value) {
        return extractValue(value, Map.of());
    }

    public static Object extractValue(Value<?> value, Map<String, Object> variables) {
        if (value instanceof VariableReference vr && variables.containsKey(vr.getName())) {
            return variables.get(vr.getName());
        }
        if (value instanceof IntValue iv) return iv.getValue().intValue();
        if (value instanceof StringValue sv) return sv.getValue();
        if (value instanceof FloatValue fv) return fv.getValue().doubleValue();
        if (value instanceof BooleanValue bv) return bv.isValue();
        if (value instanceof EnumValue ev) return ev.getName().toLowerCase();
        if (value instanceof NullValue) return null;
        if (value instanceof ObjectValue ov) return objectValueToJson(ov, variables);
        if (value instanceof ArrayValue av) return arrayValueToJson(av, variables);
        return value.toString();
    }

    private static String objectValueToJson(ObjectValue ov, Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder("{");
        List<ObjectField> fields = ov.getObjectFields();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(fields.get(i).getName()).append("\":");
            sb.append(valueToJsonString(fields.get(i).getValue(), variables));
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("rawtypes")
    private static String arrayValueToJson(ArrayValue av, Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder("[");
        List<Value> values = av.getValues();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(valueToJsonString(values.get(i), variables));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String valueToJsonString(Value<?> value, Map<String, Object> variables) {
        if (value instanceof StringValue sv) return "\"" + sv.getValue().replace("\"", "\\\"") + "\"";
        if (value instanceof IntValue iv) return iv.getValue().toString();
        if (value instanceof FloatValue fv) return fv.getValue().toString();
        if (value instanceof BooleanValue bv) return String.valueOf(bv.isValue());
        if (value instanceof EnumValue ev) return "\"" + ev.getName() + "\"";
        if (value instanceof NullValue) return "null";
        if (value instanceof ObjectValue ov) return objectValueToJson(ov, variables);
        if (value instanceof ArrayValue av) return arrayValueToJson(av, variables);
        return "\"" + value + "\"";
    }
}
