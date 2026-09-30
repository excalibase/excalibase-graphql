package io.github.excalibase.compiler;

import graphql.language.*;
import graphql.parser.Parser;
import io.github.excalibase.*;
import io.github.excalibase.schema.ExposedFunction;
import io.github.excalibase.schema.ExposedFunctions;
import io.github.excalibase.schema.GraphqlConstants;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.spi.MutationCompiler;

import java.util.*;

/**
 * Compiles GraphQL query string → single SQL with JSON aggregation.
 * Delegates to QueryBuilder, MutationBuilder, FilterBuilder via SqlDialect.
 */
public class SqlCompiler {

    /** Arguments of a function field that shape its rows rather than its call. */
    private static final Set<String> ROW_ARGUMENTS = Set.of(GraphqlConstants.ARG_WHERE, GraphqlConstants.ARG_FILTER,
            GraphqlConstants.ARG_ORDER_BY, GraphqlConstants.ARG_LIMIT, GraphqlConstants.ARG_OFFSET,
            GraphqlConstants.ARG_DISTINCT_ON, GraphqlConstants.ARG_VECTOR);

    private final SchemaInfo schemaInfo;
    private final QueryBuilder queryBuilder;
    private final MutationBuilder mutationBuilder;
    private final ThreadLocal<Map<String, FragmentDefinition>> fragmentsHolder = new ThreadLocal<>();

    private final SqlDialect dialect;
    private final int maxDepth;
    private final ExposedFunctions functions;
    private final FilterBuilder filterBuilder;

    public SqlCompiler(SchemaInfo schemaInfo, String dbSchema, int maxRows, SqlDialect dialect, MutationCompiler mutationCompiler) {
        this(schemaInfo, dbSchema, maxRows, dialect, mutationCompiler, 0);
    }

    public SqlCompiler(SchemaInfo schemaInfo, String dbSchema, int maxRows, SqlDialect dialect, MutationCompiler mutationCompiler, int maxDepth) {
        this(schemaInfo, dbSchema, maxRows, dialect, mutationCompiler, maxDepth, TableAccess.UNRESTRICTED);
    }

    /**
     * {@code schemaInfo} is already the caller's view — a table or column they may not read is not
     * in it — and {@code access} carries the rest of what the role may do on the tables that are.
     */
    public SqlCompiler(SchemaInfo schemaInfo, String dbSchema, int maxRows, SqlDialect dialect,
                       MutationCompiler mutationCompiler, int maxDepth, TableAccess access) {
        this(schemaInfo, dbSchema, maxRows, dialect, mutationCompiler, maxDepth, access, ExposedFunctions.NONE);
    }

    /** As above, plus the tracked functions the caller may call; no other function is reachable. */
    public SqlCompiler(SchemaInfo schemaInfo, String dbSchema, int maxRows, SqlDialect dialect,
                       MutationCompiler mutationCompiler, int maxDepth, TableAccess access,
                       ExposedFunctions functions) {
        this.schemaInfo = schemaInfo;
        this.dialect = dialect;
        this.maxDepth = maxDepth;
        this.functions = functions == null ? ExposedFunctions.NONE : functions;
        this.filterBuilder = new FilterBuilder(dialect, maxRows, schemaInfo, dbSchema, access);
        this.queryBuilder = new QueryBuilder(schemaInfo, dialect, filterBuilder, dbSchema, maxRows, fragmentsHolder);
        this.mutationBuilder = new MutationBuilder(schemaInfo, dialect, filterBuilder, dbSchema, queryBuilder,
                mutationCompiler, access);
    }

    public SchemaInfo schemaInfo() { return schemaInfo; }

    public SqlDialect dialect() { return dialect; }

    public ExposedFunctions functions() { return functions; }

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
        List<String> unresolvedFields = new ArrayList<>();
        for (Selection<?> sel : op.getSelectionSet().getSelections()) {
            if (sel instanceof Field field) {
                CompiledQuery earlyExit = processMutationSelection(field, params, variables, mutFragments,
                        unresolvedFields);
                if (earlyExit != null) return earlyExit;
            }
        }
        requireKnown(unresolvedFields);
        if (mutFragments.isEmpty()) {
            return new CompiledQuery("SELECT " + dialect.buildObject(List.of()), params);
        }
        if (mutFragments.size() == 1 && mutFragments.getFirst().rawSql() != null) {
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
        requireKnown(unresolvedFields);
        String sql = "SELECT " + dialect.buildObject(rootResults);
        return new CompiledQuery(sql, params);
    }

    /** A root field the caller's schema does not have fails the whole operation, never silently. */
    private static void requireKnown(List<String> unresolvedFields) {
        if (!unresolvedFields.isEmpty()) {
            throw new IllegalArgumentException("Unknown field(s): " + String.join(", ", unresolvedFields));
        }
    }

    /** Compile a single root-level query field, appending to rootResults or unresolvedFields. */
    private void compileRootQueryField(Field field, Map<String, Object> params,
                                       List<String> rootResults, List<String> unresolvedFields) {
        String fieldName = field.getName();
        String responseKey = field.getAlias() != null ? field.getAlias() : fieldName;
        Optional<ExposedFunction> function = functions.field(fieldName, ExposedFunction.Operation.QUERY);
        if (function.isPresent()) {
            rootResults.add("'" + responseKey + "', (" + compileFunctionCall(field, function.get(), params) + ")");
            return;
        }
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
        rootResults.add("'" + responseKey + "', (" + sql + ")");
    }

    /**
     * Process a single mutation selection — either appending to {@code mutFragments}
     * or returning a {@link CompiledQuery} if an early exit is needed (MySQL two-phase
     * mutation). Returns {@code null} to keep processing.
     */
    private CompiledQuery processMutationSelection(Field field, Map<String, Object> params,
                                                   Map<String, Object> variables,
                                                   List<MutationFragment> mutFragments,
                                                   List<String> unresolvedFields) {
        String fieldName = field.getName();
        String responseKey = field.getAlias() != null ? field.getAlias() : fieldName;

        Optional<ExposedFunction> function = functions.field(fieldName, ExposedFunction.Operation.MUTATION);
        if (function.isPresent()) {
            mutFragments.add(new MutationFragment(responseKey, null, compileFunctionCall(field, function.get(), params)));
            return null;
        }

        CompiledQuery frag = mutationBuilder.compileMutationFragment(field, fieldName, params, variables);
        if (frag == null) {
            if (mutationBuilder.isMutationField(fieldName)) {
                throw new IllegalArgumentException("Invalid arguments for " + fieldName);
            }
            unresolvedFields.add(fieldName);
            return null;
        }

        // MySQL two-phase mutations cannot be combined — execute immediately (single path)
        if (frag.isTwoPhase()) {
            return mutationBuilder.compileMutation(field, fieldName, params, variables);
        }

        // Use alias as response key if present (e.g. "c1: createX(...)")
        mutFragments.add(new MutationFragment(responseKey, frag.sql(), null));
        return null;
    }

    /**
     * A tracked function called with the field's own arguments; {@code where}, {@code orderBy},
     * {@code limit} and {@code offset} apply to its rows, never to the call.
     */
    private String compileFunctionCall(Field field, ExposedFunction function, Map<String, Object> params) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Argument argument : field.getArguments()) {
            boolean unboundVariable = argument.getValue() instanceof VariableReference variable
                    && !FilterBuilder.boundVariables().containsKey(variable.getName());
            if (!ROW_ARGUMENTS.contains(argument.getName()) && !unboundVariable) {
                arguments.put(argument.getName(), filterBuilder.extractValue(argument.getValue()));
            }
        }
        String call = FunctionCall.sql(function, arguments, RlsContext.sessionVariables(), dialect, params);
        return queryBuilder.compileFunction(field, function, call, params);
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
            if (fragment.rawSql() == null) {
                objectEntries.add("'" + fragment.fieldName() + "', (" + fragment.selectSql() + ")");
                continue;
            }
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

        String sql = String.join(", ", cteParts) + " SELECT " + dialect.buildObject(objectEntries);
        return new CompiledQuery(sql.trim(), params);
    }

    /** A table mutation's CTE statement ({@code rawSql}), or a function call's rows ({@code selectSql}). */
    private record MutationFragment(String fieldName, String rawSql, String selectSql) {}

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

    public record CompiledQuery(String sql, Map<String, Object> params, String dmlSql, String lastInsertIdParam) {
        public CompiledQuery(String sql, Map<String, Object> params) {
            this(sql, params, null, null);
        }
        /** True when this is a MySQL two-phase mutation (DML separate from SELECT) */
        public boolean isTwoPhase() { return dmlSql != null; }
        /** True when DELETE needs SELECT-before-DML ordering */
        public boolean isDeleteBeforeSelect() { return MutationBuilder.MUTATION_DELETE.equals(lastInsertIdParam); }
    }
}
