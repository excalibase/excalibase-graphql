package io.github.excalibase.constant;

public class FieldConstant {
    private FieldConstant() {
    }

    public static final String ORDER_BY = "orderBy";
    public static final String LIMIT = "limit";
    public static final String OFFSET = "offset";
    public static final String EDGES = "edges";
    public static final String HAS_NEXT_PAGE = "hasNextPage";
    public static final String HAS_PREVIOUS_PAGE = "hasPreviousPage";
    public static final String PAGE_INFO = "pageInfo";
    public static final String START_CURSOR = "startCursor";
    public static final String END_CURSOR = "endCursor";
    public static final String TOTAL_COUNT = "totalCount";
    public static final String ERROR = "error";
    public static final String NODE = "node";
    public static final String CURSOR = "cursor";
    public static final String FIRST = "first";
    public static final String LAST = "last";
    public static final String BEFORE = "before";
    public static final String AFTER = "after";

    // Query operators
    public static final String OPERATOR_CONTAINS = "contains";
    public static final String OPERATOR_STARTS_WITH = "startsWith";
    public static final String OPERATOR_ENDS_WITH = "endsWith";
    public static final String OPERATOR_GT = "gt";
    public static final String OPERATOR_GTE = "gte";
    public static final String OPERATOR_LT = "lt";
    public static final String OPERATOR_LTE = "lte";
    public static final String OPERATOR_IS_NULL = "isNull";
    public static final String OPERATOR_IS_NOT_NULL = "isNotNull";

    // Context keys
    public static final String BATCH_CONTEXT = "BATCH_CONTEXT";
}
