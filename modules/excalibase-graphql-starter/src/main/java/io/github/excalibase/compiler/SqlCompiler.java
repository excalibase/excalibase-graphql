package io.github.excalibase.compiler;

import graphql.language.*;
import graphql.parser.Parser;
import io.github.excalibase.*;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.MutationCompiler;

import java.util.*;

/**
 * Compiles GraphQL query string → single SQL with JSON aggregation.
 * Delegates to QueryBuilder, MutationBuilder, FilterBuilder via SqlDialect.
 */
public class SqlCompiler {

    private final SchemaInfo schemaInfo;
    private final QueryBuilder queryBuilder;
    private final MutationBuilder mutationBuilder;
    private final ThreadLocal<Map<String, FragmentDefinition>> fragmentsHolder = new ThreadLocal<>();

    private final SqlDialect dialect;
    private final int maxDepth;

    public SqlCompiler(SchemaInfo schemaInfo, String dbSchema, int maxRows, SqlDialect dialect, MutationCompiler mutationCompiler) {
        this(schemaInfo, dbSchema, maxRows, dialect, mutationCompiler, 0);
    }

    public SqlCompiler(SchemaInfo schemaInfo, String dbSchema, int maxRows, SqlDialect dialect, MutationCompiler mutationCompiler, int maxDepth) {
        this.schemaInfo = schemaInfo;
        this.dialect = dialect;
        this.maxDepth = maxDepth;
        FilterBuilder filterBuilder = new FilterBuilder(dialect, maxRows, schemaInfo, dbSchema);
        this.queryBuilder = new QueryBuilder(schemaInfo, dialect, filterBuilder, dbSchema, maxRows, fragmentsHolder);
        this.mutationBuilder = new MutationBuilder(schemaInfo, dialect, filterBuilder, dbSchema, queryBuilder, mutationCompiler);
    }

    public SchemaInfo schemaInfo() { return schemaInfo; }

    public SqlDialect dialect() { return dialect; }

    public CompiledQuery compile(String queryString) {
        return compile(queryString, Map.of());
    }

    public CompiledQuery compile(String queryString, Map<String, Object> variables) {
        Document doc = Parser.parse(queryString);
        var ops = doc.getDefinitionsOfType(OperationDefinition.class);
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("Query must contain an operation");
        }
        OperationDefinition op = ops.getFirst();

        // Collect fragment definitions for expansion
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        for (var def : doc.getDefinitionsOfType(FragmentDefinition.class)) {
            fragments.put(def.getName(), def);
        }

        // Optional depth limit check
        if (maxDepth > 0) {
            int depth = measureDepth(op.getSelectionSet(), fragments);
            if (depth > maxDepth) {
                throw new IllegalArgumentException("Query depth " + depth + " exceeds maximum allowed depth of " + maxDepth);
            }
        }

        fragmentsHolder.set(fragments);

        try {
            boolean isMutation = op.getOperation() == OperationDefinition.Operation.MUTATION;
            Map<String, Object> params = new HashMap<>();

            if (isMutation) {
                for (Selection<?> sel : op.getSelectionSet().getSelections()) {
                    if (sel instanceof Field field) {
                        String fieldName = field.getName();

                        // Stored procedure calls — works on both Postgres and MySQL
                        if (fieldName.startsWith("call")) {
                            String procNameResolved = mutationBuilder.resolveStoredProcedure(fieldName.substring("call".length()));
                            if (procNameResolved != null) {
                                ProcedureCallInfo callInfo = mutationBuilder.buildProcedureCallInfo(
                                        field, procNameResolved, params, variables);
                                if (callInfo != null) {
                                    return new CompiledQuery(null, params, null, null,
                                            true, fieldName, callInfo);
                                }
                            }
                        }

                        // Compile mutation (delegates to PG or MySQL compiler internally)
                        CompiledQuery mutationResult = mutationBuilder.compileMutation(field, fieldName, params, variables);
                        if (mutationResult != null) {
                            return mutationResult;
                        }
                    }
                }
                String emptyObj = dialect.buildObject(List.of());
                return new CompiledQuery("SELECT " + emptyObj, params);
            }

            List<String> rootResults = new ArrayList<>();
            List<String> unresolvedFields = new ArrayList<>();
            for (Selection<?> sel : op.getSelectionSet().getSelections()) {
                if (sel instanceof Field field) {
                    String fieldName = field.getName();
                    String tableName = queryBuilder.resolveTableName(fieldName);
                    if (tableName == null) {
                        unresolvedFields.add(fieldName);
                        continue;
                    }

                    boolean isConnection = fieldName.endsWith("Connection");
                    boolean isAggregate = fieldName.endsWith("Aggregate");
                    String sql = isAggregate
                            ? queryBuilder.compileAggregate(field, tableName, params)
                            : isConnection
                            ? queryBuilder.compileConnection(field, tableName, params)
                            : queryBuilder.compileList(field, tableName, params);

                    rootResults.add("'" + fieldName + "', (" + sql + ")");
                }
            }

            // If no fields could be resolved, return an error
            if (rootResults.isEmpty() && !unresolvedFields.isEmpty()) {
                throw new IllegalArgumentException("Unknown field(s): " + String.join(", ", unresolvedFields));
            }

            String sql = "SELECT " + dialect.buildObject(rootResults);
            return new CompiledQuery(sql, params);
        } finally {
            fragmentsHolder.remove();
        }
    }

    private int measureDepth(SelectionSet selectionSet, Map<String, FragmentDefinition> fragments) {
        if (selectionSet == null || selectionSet.getSelections().isEmpty()) return 0;
        int max = 0;
        for (Selection<?> sel : selectionSet.getSelections()) {
            if (sel instanceof Field f) {
                int childDepth = measureDepth(f.getSelectionSet(), fragments);
                max = Math.max(max, 1 + childDepth);
            } else if (sel instanceof InlineFragment inf) {
                max = Math.max(max, measureDepth(inf.getSelectionSet(), fragments));
            } else if (sel instanceof FragmentSpread fs) {
                FragmentDefinition frag = fragments.get(fs.getName());
                if (frag != null) {
                    max = Math.max(max, measureDepth(frag.getSelectionSet(), fragments));
                }
            }
        }
        return max;
    }

    /**
     * Check if a query is an introspection query by parsing the first field.
     */
    public boolean isIntrospection(String queryString) {
        Document doc = Parser.parse(queryString);
        var ops = doc.getDefinitionsOfType(OperationDefinition.class);
        if (ops.isEmpty()) return false;
        OperationDefinition op = ops.getFirst();
        for (Selection<?> sel : op.getSelectionSet().getSelections()) {
            if (sel instanceof Field f && f.getName().startsWith("__")) {
                return true;
            }
        }
        return false;
    }

    public record CompiledQuery(String sql, Map<String, Object> params, String dmlSql, String lastInsertIdParam,
                                boolean isProcedureCall, String mutationFieldName,
                                ProcedureCallInfo procedureCallInfo) {
        public CompiledQuery(String sql, Map<String, Object> params) {
            this(sql, params, null, null, false, null, null);
        }
        public CompiledQuery(String sql, Map<String, Object> params, String dmlSql, String lastInsertIdParam) {
            this(sql, params, dmlSql, lastInsertIdParam, false, null, null);
        }
        public CompiledQuery(String sql, Map<String, Object> params, String dmlSql, String lastInsertIdParam,
                             boolean isProcedureCall, String mutationFieldName) {
            this(sql, params, dmlSql, lastInsertIdParam, isProcedureCall, mutationFieldName, null);
        }
        /** True when this is a MySQL two-phase mutation (DML separate from SELECT) */
        public boolean isTwoPhase() { return dmlSql != null; }
        /** True when DELETE needs SELECT-before-DML ordering */
        public boolean isDeleteBeforeSelect() { return MutationBuilder.MUTATION_DELETE.equals(lastInsertIdParam); }
    }

    /** Info needed to execute a stored procedure via CALL with CallableStatement */
    public record ProcedureCallInfo(String qualifiedName, java.util.List<ProcedureCallParam> allParams) {}
    public record ProcedureCallParam(String name, String mode, String type, Object value) {}
}
