package io.github.excalibase.compiler;

import graphql.language.*;
import io.github.excalibase.*;
import io.github.excalibase.schema.NamingUtils;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.WriteGuard;
import io.github.excalibase.spi.MutationCompiler;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

import static io.github.excalibase.compiler.SqlKeywords.P_BULK_INSERT;
import static io.github.excalibase.compiler.SqlKeywords.assignWithCast;
import static io.github.excalibase.compiler.SqlKeywords.joinCols;
import static io.github.excalibase.compiler.SqlKeywords.namedParam;
import static io.github.excalibase.compiler.SqlKeywords.param;
import static io.github.excalibase.compiler.SqlKeywords.parens;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUTS;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.COLLECTION_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_MANY_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_FROM_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.UPDATE_PREFIX;

/**
 * Mutation router and shared helpers. Delegates to a MutationCompiler
 * implementation for dialect-specific mutation compilation.
 */
public class MutationBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SchemaInfo schemaInfo;
    private final SqlDialect dialect;
    private final FilterBuilder filterBuilder;
    private final String dbSchema;
    private final QueryBuilder queryBuilder;

    private final MutationCompiler mutationCompiler;
    private final TableAccess access;

    public MutationBuilder(SchemaInfo schemaInfo, SqlDialect dialect, FilterBuilder filterBuilder,
                           String dbSchema, QueryBuilder queryBuilder, MutationCompiler mutationCompiler) {
        this(schemaInfo, dialect, filterBuilder, dbSchema, queryBuilder, mutationCompiler,
                TableAccess.UNRESTRICTED);
    }

    public MutationBuilder(SchemaInfo schemaInfo, SqlDialect dialect, FilterBuilder filterBuilder,
                           String dbSchema, QueryBuilder queryBuilder, MutationCompiler mutationCompiler,
                           TableAccess access) {
        this.schemaInfo = schemaInfo;
        this.dialect = dialect;
        this.filterBuilder = filterBuilder;
        this.dbSchema = dbSchema;
        this.queryBuilder = queryBuilder;
        this.mutationCompiler = mutationCompiler;
        this.access = access == null ? TableAccess.UNRESTRICTED : access;
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

    public SqlCompiler.CompiledQuery compileMutationFragment(Field field, String fieldName,
                                                             Map<String, Object> params, Map<String, Object> variables) {
        return mutationCompiler.compileMutationFragment(field, fieldName, params, variables, this);
    }

    // === Table name resolution ===

    /**
     * Resolves the table behind a mutation field, and is the one place a mutation
     * field comes into existence. A field the caller was not granted resolves to
     * {@code null} — the same answer an unknown table gives — so the caller sees
     * "unknown field" rather than a denial, because for them the field is not part
     * of the schema.
     */
    public String resolveMutationTable(String typeName, String mutationFieldName) {
        String tableName = lookupTable(typeName);
        if (tableName == null) {
            return null;
        }
        RlsOp operation = operationOf(mutationFieldName);
        return (operation == null || access.permits(tableName, operation)) ? tableName : null;
    }

    /** True when {@code fieldName} is a table mutation the caller's schema has, whatever its arguments. */
    public boolean isMutationField(String fieldName) {
        for (String prefix : List.of(DELETE_FROM_PREFIX, CREATE_MANY_PREFIX, CREATE_PREFIX, UPDATE_PREFIX,
                DELETE_PREFIX)) {
            if (fieldName.startsWith(prefix)) {
                String typePart = fieldName.substring(prefix.length());
                if (typePart.endsWith(COLLECTION_SUFFIX)) {
                    typePart = typePart.substring(0, typePart.length() - COLLECTION_SUFFIX.length());
                }
                if (resolveMutationTable(typePart, fieldName) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when the caller may upsert into {@code tableName}. {@code create} with
     * {@code onConflict} compiles to {@code DO UPDATE}, which overwrites rows that
     * already exist, so it needs UPDATE on top of INSERT.
     */
    public boolean permitsUpsert(String tableName) {
        return access.permits(tableName, RlsOp.UPDATE);
    }

    public TableAccess access() {
        return access;
    }

    /**
     * Refuses a column the caller may not set for {@code operation}: one the role's permission does not
     * list, a preset one, or one the table does not have. Without this a client could write any real
     * column by naming it. See {@link TableAccess#requireSettable} for how each is reported.
     */
    public void requireSettable(String tableName, RlsOp operation, Collection<String> columns) {
        access.requireSettable(tableName, operation, columns, schemaInfo);
    }

    private static RlsOp operationOf(String mutationFieldName) {
        if (mutationFieldName == null) {
            return null;
        }
        if (mutationFieldName.startsWith(CREATE_PREFIX)) {
            return RlsOp.INSERT;
        }
        if (mutationFieldName.startsWith(UPDATE_PREFIX)) {
            return RlsOp.UPDATE;
        }
        if (mutationFieldName.startsWith(DELETE_PREFIX)) {
            return RlsOp.DELETE;
        }
        return null;
    }

    /** A readable table, or one the caller may only insert into; the operation's permission decides the rest. */
    String lookupTable(String typeName) {
        String snake = NamingUtils.camelToSnakeCase(typeName);
        if (writable(snake)) return snake;
        String lower = typeName.toLowerCase();
        if (writable(lower)) return lower;

        // Compound keys: match prefixed type name (e.g., "TestSchemaCustomer" → "test_schema.customer")
        for (String table : schemaInfo.getMutableTableNames()) {
            if (!table.contains(".")) continue;
            String schema = table.substring(0, table.indexOf('.'));
            String rawTable = table.substring(table.indexOf('.') + 1);
            if (NamingUtils.schemaTypeName(schema, rawTable).equals(typeName)) return table;
        }
        return null;
    }

    private boolean writable(String table) {
        return schemaInfo.hasTable(table) || schemaInfo.isWriteOnlyTable(table);
    }

    /** False for a table the caller may insert into but not select: its writes answer only a count. */
    public boolean returnsRows(String tableName) {
        return access.permits(tableName, RlsOp.SELECT);
    }

    /**
     * What a write of {@code tableName} returns per row: the row as the caller reads it, or, when the
     * caller may not read the table, the {@code affected_rows} aggregate over the written rows.
     */
    public String resultObject(Field field, String tableName, String alias, Map<String, Object> params) {
        return returnsRows(tableName)
                ? queryBuilder.buildObject(field.getSelectionSet(), tableName, alias, params)
                : queryBuilder.buildAffectedRows(field.getSelectionSet());
    }

    // === Shared helpers (public, used by dialect-specific mutation compilers) ===

    /** A multi-row INSERT's result object, quoted column list and VALUES rows. */
    public record BulkInsertParts(String alias, String objectSql, String columns, String valueRows, int rowCount) {}

    /** A single-table UPDATE's result object and SET clauses. */
    public record UpdateParts(String alias, String objectSql, List<String> setClauses) {}

    /**
     * Builds the parts of {@code createMany} every dialect shares; {@code null} without an
     * {@code inputs} argument or rows. Every row may set only the columns the caller may insert, and
     * the permission's presets are written into every row.
     */
    public BulkInsertParts bulkInsertParts(Field field, String tableName, Map<String, Object> params,
                                           Map<String, Object> variables, boolean castParams) {
        Argument inputsArg = findArg(field, ARG_INPUTS);
        if (inputsArg == null) return null;
        return bulkInsertParts(field, tableName, extractArrayOfObjects(inputsArg.getValue(), variables), params,
                castParams);
    }

    /** As above, from rows already read from the {@code inputs} argument. */
    public BulkInsertParts bulkInsertParts(Field field, String tableName, List<Map<String, Object>> rows,
                                           Map<String, Object> params, boolean castParams) {
        if (rows.isEmpty()) return null;
        rows.forEach(row -> requireSettable(tableName, RlsOp.INSERT, row.keySet()));
        Map<String, Object> presets = presets(tableName, RlsOp.INSERT);

        String alias = dialect.randAlias();
        String objectSql = resultObject(field, tableName, alias, params);
        List<String> colNames = new ArrayList<>(rows.getFirst().keySet());
        colNames.addAll(presets.keySet());
        List<String> valueRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = withPresets(rows.get(i), presets);
            List<String> vals = new ArrayList<>();
            for (String col : colNames) {
                String paramName = namedParam(P_BULK_INSERT, col + "_" + i, params.size());
                vals.add(param(paramName) + castFor(tableName, col, castParams));
                params.put(paramName, row.get(col));
            }
            valueRows.add(parens(joinCols(vals)));
        }
        String columns = joinCols(colNames.stream().map(dialect::quoteIdentifier).toList());
        return new BulkInsertParts(alias, objectSql, columns, joinCols(valueRows), rows.size());
    }

    /**
     * Builds the result object and SET clauses of an UPDATE from {@code setFields} plus the
     * permission's presets; the result object is built first so its binds precede the SET binds.
     */
    public UpdateParts updateParts(Field field, String tableName, Map<String, Object> setFields,
                                   String paramPrefix, Map<String, Object> params, boolean castParams) {
        requireSettable(tableName, RlsOp.UPDATE, setFields.keySet());
        String alias = dialect.randAlias();
        String objectSql = queryBuilder.buildObject(field.getSelectionSet(), tableName, alias, params);
        List<String> setClauses = new ArrayList<>();
        for (var entry : withPresets(setFields, presets(tableName, RlsOp.UPDATE)).entrySet()) {
            String paramName = namedParam(paramPrefix, entry.getKey(), params.size());
            setClauses.add(assignWithCast(dialect.quoteIdentifier(entry.getKey()), paramName,
                    castFor(tableName, entry.getKey(), castParams)));
            params.put(paramName, entry.getValue());
        }
        return new UpdateParts(alias, objectSql, setClauses);
    }

    /** The values the caller's permission presets for {@code operation}; empty without a guard. */
    public Map<String, Object> presets(String tableName, RlsOp operation) {
        WriteGuard guard = RlsContext.writeGuard();
        return guard == null ? Map.of() : guard.presets(tableName, operation);
    }

    /** {@code row} with the presets written over it; a preset always wins. */
    public static Map<String, Object> withPresets(Map<String, Object> row, Map<String, Object> presets) {
        Map<String, Object> merged = new LinkedHashMap<>(row);
        merged.putAll(presets);
        return merged;
    }

    private String castFor(String tableName, String colName, boolean castParams) {
        return castParams ? getEnumCastForMutation(tableName, colName) : "";
    }

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
        if (value instanceof String stringValue && stringValue.startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(stringValue, Map.class);
                return buildTupleString(fields, parsed);
            } catch (Exception _) {
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
            for (Value<?> elementValue : av.getValues()) {
                result.add(extractObjectFields(elementValue, variables));
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
        if (value instanceof EnumValue ev) return ev.getName();
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

    private static String arrayValueToJson(ArrayValue av, Map<String, Object> variables) {
        StringBuilder sb = new StringBuilder("[");
        @SuppressWarnings("unchecked")
        List<Value<?>> values = (List<Value<?>>) (List<?>) av.getValues();
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
