package io.github.excalibase.schema;

/**
 * Shared constants for GraphQL field names, argument names, and mutation prefixes.
 * Eliminates hardcoded strings across compiler, introspection, and mutation code.
 */
public final class GraphqlConstants {

    private GraphqlConstants() {}

    // --- Mutation prefixes ---
    public static final String CREATE_PREFIX = "create";
    public static final String CREATE_MANY_PREFIX = "createMany";
    public static final String UPDATE_PREFIX = "update";
    public static final String DELETE_PREFIX = "delete";
    public static final String DELETE_FROM_PREFIX = "deleteFrom";
    public static final String CALL_PREFIX = "call";

    // --- Mutation/query suffixes ---
    public static final String COLLECTION_SUFFIX = "Collection";
    public static final String CONNECTION_SUFFIX = "Connection";
    public static final String AGGREGATE_SUFFIX = "Aggregate";
    public static final String EDGE_SUFFIX = "Edge";
    public static final String WHERE_INPUT_SUFFIX = "WhereInput";
    public static final String CREATE_INPUT_SUFFIX = "CreateInput";

    // --- Argument names ---
    public static final String ARG_WHERE = "where";
    public static final String ARG_INPUT = "input";
    public static final String ARG_INPUTS = "inputs";
    public static final String ARG_FILTER = "filter";
    public static final String ARG_SET = "set";
    public static final String ARG_ID = "id";
    public static final String ARG_FIRST = "first";
    public static final String ARG_AFTER = "after";
    public static final String ARG_LAST = "last";
    public static final String ARG_BEFORE = "before";
    public static final String ARG_LIMIT = "limit";
    public static final String ARG_OFFSET = "offset";
    public static final String ARG_ORDER_BY = "orderBy";
    public static final String ARG_DISTINCT_ON = "distinctOn";
    public static final String ARG_ON_CONFLICT = "onConflict";
    public static final String ARG_AT_MOST = "atMost";

    // --- On-conflict sub-fields ---
    public static final String ON_CONFLICT_CONSTRAINT = "constraint";
    public static final String ON_CONFLICT_UPDATE_COLUMNS = "update_columns";

    // --- Built-in type names ---
    public static final String TYPE_QUERY = "Query";
    public static final String TYPE_MUTATION = "Mutation";
    public static final String TYPE_PAGE_INFO = "PageInfo";

    // --- PageInfo / Connection fields ---
    public static final String FIELD_EDGES = "edges";
    public static final String FIELD_NODE = "node";
    public static final String FIELD_CURSOR = "cursor";
    public static final String FIELD_PAGE_INFO = "pageInfo";
    public static final String FIELD_TOTAL_COUNT = "totalCount";
    public static final String FIELD_HAS_NEXT_PAGE = "hasNextPage";
    public static final String FIELD_HAS_PREVIOUS_PAGE = "hasPreviousPage";
    public static final String FIELD_START_CURSOR = "startCursor";
    public static final String FIELD_END_CURSOR = "endCursor";
    public static final String FIELD_COUNT = "count";

    // --- Aggregate function names ---
    public static final String AGG_SUM = "sum";
    public static final String AGG_AVG = "avg";
    public static final String AGG_MIN = "min";
    public static final String AGG_MAX = "max";

    // --- Filter operators ---
    public static final String FILTER_EQ = "eq";
    public static final String FILTER_NEQ = "neq";
    public static final String FILTER_GT = "gt";
    public static final String FILTER_GTE = "gte";
    public static final String FILTER_LT = "lt";
    public static final String FILTER_LTE = "lte";
    public static final String FILTER_LIKE = "like";
    public static final String FILTER_ILIKE = "ilike";
    public static final String FILTER_IN = "in";
    public static final String FILTER_NOT_IN = "notIn";
    public static final String FILTER_IS_NULL = "isNull";
    public static final String FILTER_IS_NOT_NULL = "isNotNull";
    public static final String FILTER_CONTAINS = "contains";
    public static final String FILTER_STARTS_WITH = "startsWith";
    public static final String FILTER_ENDS_WITH = "endsWith";
    /**
     * Full-text search operator. Dispatches via {@link io.github.excalibase.SqlDialect#fullTextSearchSql}
     * to emit {@code col @@ plainto_tsquery(:p)} against a tsvector column.
     */
    public static final String FILTER_SEARCH = "_search";

    // --- Logical operators ---
    public static final String LOGICAL_AND = "AND";
    public static final String LOGICAL_OR = "OR";
    public static final String LOGICAL_NOT = "NOT";

    // --- Placeholder for empty query ---
    public static final String EMPTY_FIELD = "_empty";
}
