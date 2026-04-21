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
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        try {
            return ScopedValue.where(FilterBuilder.CURRENT_VARIABLES, vars)
                    .call(() -> doCompile(queryString, vars));
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new SqlCompilationException("GraphQL query compilation failed", e);
        }
    }

    private CompiledQuery doCompile(String queryString, Map<String, Object> variables) {
        Document doc = Parser.parse(queryString);
        var ops = doc.getDefinitionsOfType(OperationDefinition.class);
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("Query must contain an operation");
        }
        OperationDefinition op = ops.getFirst();

        Map<String, FragmentDefinition> fragments = collectFragments(doc);
        enforceMaxDepth(op, fragments);
        fragmentsHolder.set(fragments);

        try {
            Map<String, Object> params = new HashMap<>();
            if (op.getOperation() == OperationDefinition.Operation.MUTATION) {
                return compileMutationOperation(op, params, variables);
            }
            return compileQueryOperation(op, params);
        } finally {
            fragmentsHolder.remove();
        }
    }

    /** Collect all fragment definitions from the document for later expansion. */
    private Map<String, FragmentDefinition> collectFragments(Document doc) {
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        for (var def : doc.getDefinitionsOfType(FragmentDefinition.class)) {
            fragments.put(def.getName(), def);
        }
        return fragments;
    }

    /** Throws IllegalArgumentException when the operation exceeds the configured max depth. */
    private void enforceMaxDepth(OperationDefinition op, Map<String, FragmentDefinition> fragments) {
        if (maxDepth <= 0) return;
        int depth = measureDepth(op.getSelectionSet(), fragments);
        if (depth > maxDepth) {
            throw new IllegalArgumentException("Query depth " + depth + " exceeds maximum allowed depth of " + maxDepth);
        }
    }

    /** Compile a MUTATION operation — produces a single CTE or a combined multi-mutation statement. */
    private CompiledQuery compileMutationOperation(OperationDefinition op, Map<String, Object> params,
                                                   Map<String, Object> variables) {
        List<MutationFragment> mutFragments = new ArrayList<>();
        for (Selection<?> sel : op.getSelectionSet().getSelections()) {
            if (sel instanceof Field field) {
                CompiledQuery earlyExit = processMutationSelection(field, params, variables, mutFragments);
                if (earlyExit != null) return earlyExit;
            }
        }
        if (mutFragments.isEmpty()) {
            return new CompiledQuery("SELECT " + dialect.buildObject(List.of()), params);
        }
        if (mutFragments.size() == 1) {
            // Single mutation — use existing wrapped path (backward compatible)
            String wrapped = dialect.wrapMutationResult(mutFragments.get(0).rawSql(), mutFragments.get(0).fieldName());
            return new CompiledQuery(wrapped, params);
        }
        return combineFragments(mutFragments, params);
    }

    /** Compile a QUERY operation — each root selection becomes one JSON entry. */
    private CompiledQuery compileQueryOperation(OperationDefinition op, Map<String, Object> params) {
        List<String> rootResults = new ArrayList<>();
        List<String> unresolvedFields = new ArrayList<>();
        for (Selection<?> sel : op.getSelectionSet().getSelections()) {
            if (sel instanceof Field field) {
                compileRootQueryField(field, params, rootResults, unresolvedFields);
            }
        }
        if (rootResults.isEmpty() && !unresolvedFields.isEmpty()) {
            throw new IllegalArgumentException("Unknown field(s): " + String.join(", ", unresolvedFields));
        }
        String sql = "SELECT " + dialect.buildObject(rootResults);
        return new CompiledQuery(sql, params);
    }

    /** Compile a single root-level query field, appending to rootResults or unresolvedFields. */
    private void compileRootQueryField(Field field, Map<String, Object> params,
                                       List<String> rootResults, List<String> unresolvedFields) {
        String fieldName = field.getName();
        String tableName = queryBuilder.resolveTableName(fieldName);
        if (tableName == null) {
            unresolvedFields.add(fieldName);
            return;
        }
        String sql;
        if (fieldName.endsWith("Aggregate")) {
            sql = queryBuilder.compileAggregate(field, tableName, params);
        } else if (fieldName.endsWith("Connection")) {
            sql = queryBuilder.compileConnection(field, tableName, params);
        } else {
            sql = queryBuilder.compileList(field, tableName, params);
        }
        String responseKey = field.getAlias() != null ? field.getAlias() : fieldName;
        rootResults.add("'" + responseKey + "', (" + sql + ")");
    }

    /**
     * Process a single mutation selection — either appending to {@code mutFragments}
     * or returning a {@link CompiledQuery} if an early exit is needed (stored procedure
     * call or MySQL two-phase mutation). Returns {@code null} to keep processing.
     */
    private CompiledQuery processMutationSelection(Field field, Map<String, Object> params,
                                                   Map<String, Object> variables,
                                                   List<MutationFragment> mutFragments) {
        String fieldName = field.getName();

        // Stored procedure calls — single call, return immediately
        if (fieldName.startsWith("call")) {
            String procNameResolved = mutationBuilder.resolveStoredProcedure(fieldName.substring("call".length()));
            if (procNameResolved != null) {
                ProcedureCallInfo callInfo = mutationBuilder.buildProcedureCallInfo(field, procNameResolved, variables);
                if (callInfo != null) {
                    return new CompiledQuery(null, params, null, null, true, fieldName, callInfo);
                }
            }
        }

        CompiledQuery frag = mutationBuilder.compileMutationFragment(field, fieldName, params, variables);
        if (frag == null) return null;

        // MySQL two-phase mutations cannot be combined — execute immediately (single path)
        if (frag.isTwoPhase()) {
            return mutationBuilder.compileMutation(field, fieldName, params, variables);
        }

        // Use alias as response key if present (e.g. "c1: createX(...)")
        String responseKey = field.getAlias() != null ? field.getAlias() : fieldName;
        mutFragments.add(new MutationFragment(responseKey, frag.sql()));
        return null;
    }

    /**
     * Combines multiple CTE mutation fragments into a single atomic SQL statement.
     *
     * <p>Each raw fragment looks like:
     * {@code WITH "alias" AS (INSERT/UPDATE/DELETE ... RETURNING *) SELECT objectSql FROM "alias"}
     *
     * <p>Combined result:
     * {@code WITH "a1" AS (...), "a2" AS (...) SELECT jsonb_build_object('f1', (...), 'f2', (...))}
     */
    private CompiledQuery combineFragments(List<MutationFragment> fragments, Map<String, Object> params) {
        List<String> cteParts = new ArrayList<>();
        List<String> objectEntries = new ArrayList<>();

        for (MutationFragment fragment : fragments) {
            // Split at last ") SELECT" boundary to separate CTE body from SELECT clause
            int splitIdx = fragment.rawSql().lastIndexOf(") SELECT ");
            if (splitIdx == -1) continue;

            String ctePart = fragment.rawSql().substring(0, splitIdx + 1); // includes closing ")"
            String selectPart = fragment.rawSql().substring(splitIdx + 2);  // "SELECT ..."

            // Strip "WITH " prefix from 2nd+ fragments (chained with comma)
            if (!cteParts.isEmpty() && ctePart.startsWith("WITH ")) {
                ctePart = ctePart.substring("WITH ".length());
            }
            cteParts.add(ctePart);
            objectEntries.add("'" + fragment.fieldName() + "', (" + selectPart + ")");
        }

        String sql = String.join(", ", cteParts) + " SELECT jsonb_build_object(" + String.join(", ", objectEntries) + ")";
        return new CompiledQuery(sql, params);
    }

    private record MutationFragment(String fieldName, String rawSql) {}

    private int measureDepth(SelectionSet selectionSet, Map<String, FragmentDefinition> fragments) {
        if (selectionSet == null || selectionSet.getSelections().isEmpty()) return 0;
        int max = 0;
        for (Selection<?> sel : selectionSet.getSelections()) {
            if (sel instanceof Field field) {
                int childDepth = measureDepth(field.getSelectionSet(), fragments);
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
            if (sel instanceof Field field && field.getName().startsWith("__")) {
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
    public record ProcedureCallInfo(String qualifiedName, List<ProcedureCallParam> allParams) {}
    public record ProcedureCallParam(String name, String mode, String type, Object value) {}
}
