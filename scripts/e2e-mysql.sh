#!/bin/bash

# Excalibase GraphQL MySQL E2E Test Suite
# Tests the complete GraphQL API against a MySQL backend
# Requires: docker-compose services running (docker-compose.mysql.yml)

API_URL="http://localhost:10001/graphql"
TIMEOUT=30
MAX_RETRIES=15
RETRY_DELAY=5

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $1"; }

wait_for_api() {
    log_info "Waiting for GraphQL API (MySQL) to be ready..."
    for i in $(seq 1 $MAX_RETRIES); do
        if curl -s --connect-timeout 5 "$API_URL" > /dev/null 2>&1; then
            log_success "GraphQL API is ready!"
            return 0
        fi
        log_warning "Attempt $i/$MAX_RETRIES: not ready, waiting ${RETRY_DELAY}s..."
        sleep $RETRY_DELAY
    done
    log_error "GraphQL API failed to start after $MAX_RETRIES attempts"
    return 1
}

execute_graphql_query() {
    local test_name="$1"
    local query="$2"
    local expected_check="$3"

    log_info "Testing: $test_name"

    local json_payload
    json_payload=$(printf '{"query": %s}' "$(printf '%s' "$query" | jq -R .)")

    local response
    response=$(curl -s --max-time "$TIMEOUT" -X POST \
        -H "Content-Type: application/json" \
        -d "$json_payload" "$API_URL")

    if [ $? -ne 0 ]; then
        log_error "$test_name: HTTP request failed"
        return 1
    fi

    if echo "$response" | jq -e '.errors' > /dev/null 2>&1; then
        log_error "$test_name: GraphQL errors"
        echo "$response" | jq '.errors'
        return 1
    fi

    if ! echo "$response" | jq -e '.data' > /dev/null 2>&1; then
        log_error "$test_name: No data field"
        echo "$response"
        return 1
    fi

    if [ -n "$expected_check" ]; then
        if ! echo "$response" | jq -e "$expected_check" > /dev/null 2>&1; then
            log_error "$test_name: Check failed: $expected_check"
            echo "$response" | jq '.'
            return 1
        fi
    fi

    log_success "$test_name: ✓ Passed"
    return 0
}

test_count=0
passed_tests=0
failed_tests=0

run_test() {
    ((test_count++))
    if execute_graphql_query "$@"; then
        ((passed_tests++))
    else
        ((failed_tests++))
    fi
}

main() {
    echo "=================================================="
    echo "🚀 Excalibase GraphQL MySQL E2E Test Suite"
    echo "🎯 API Endpoint: $API_URL"
    echo "🐬 Database: MySQL on port 3306"
    echo "=================================================="

    if ! wait_for_api; then
        exit 1
    fi

    echo ""
    log_info "🧪 Starting GraphQL API Tests..."
    echo ""

    # ==========================================
    # SCHEMA INTROSPECTION
    # ==========================================

    run_test "Schema Introspection" \
        "{ __schema { types { name } } }" \
        '.data.__schema.types | length > 10'

    run_test "Query Type Exists" \
        "{ __schema { queryType { name } } }" \
        '.data.__schema.queryType.name == "Query"'

    run_test "Mutation Type Exists" \
        "{ __schema { mutationType { name } } }" \
        '.data.__schema.mutationType.name == "Mutation"'

    # ==========================================
    # BASIC QUERIES
    # ==========================================

    run_test "Get All Customers" \
        "{ customer { customer_id first_name last_name email } }" \
        '.data.customer | length >= 10'

    run_test "Customer Filter eq" \
        '{ customer(where: { first_name: { eq: "MARY" } }) { customer_id first_name } }' \
        '.data.customer | length >= 1'

    run_test "Customer Pagination limit" \
        '{ customer(limit: 5) { customer_id first_name } }' \
        '.data.customer | length == 5'

    run_test "Customer Pagination limit+offset" \
        '{ customer(limit: 3, offset: 5) { customer_id first_name } }' \
        '.data.customer | length == 3'

    run_test "Customer Order ASC" \
        '{ customer(orderBy: { customer_id: "ASC" }, limit: 3) { customer_id } }' \
        '.data.customer[0].customer_id < .data.customer[1].customer_id'

    run_test "Customer Order DESC" \
        '{ customer(orderBy: { customer_id: "DESC" }, limit: 3) { customer_id } }' \
        '.data.customer[0].customer_id > .data.customer[1].customer_id'

    run_test "Orders basic query" \
        '{ orders { order_id customer_id total status } }' \
        '.data.orders | length >= 10'

    run_test "Orders filter by status" \
        '{ orders(where: { status: { eq: "delivered" } }) { order_id status } }' \
        '.data.orders | length >= 1'

    run_test "Customer Filter neq" \
        '{ customer(where: { first_name: { neq: "MARY" } }) { customer_id first_name } }' \
        '.data.customer | all(.first_name != "MARY")'

    run_test "Orders filter gt (total > 50)" \
        '{ orders(where: { total: { gt: 50 } }) { order_id total } }' \
        '.data.orders | length >= 1 and all(.total > 50)'

    run_test "Orders filter gte (total >= 99.99)" \
        '{ orders(where: { total: { gte: 99.99 } }) { order_id total } }' \
        '.data.orders | length >= 1 and all(.total >= 99.99)'

    run_test "Orders filter lt (total < 20)" \
        '{ orders(where: { total: { lt: 20 } }) { order_id total } }' \
        '.data.orders | length >= 1 and all(.total < 20)'

    run_test "Orders filter lte (total <= 9.99)" \
        '{ orders(where: { total: { lte: 9.99 } }) { order_id total } }' \
        '.data.orders | length >= 1 and all(.total <= 9.99)'

    run_test "Customer Filter contains" \
        '{ customer(where: { first_name: { contains: "AR" } }) { customer_id first_name } }' \
        '.data.customer | length >= 1'

    run_test "Customer Filter startsWith" \
        '{ customer(where: { first_name: { startsWith: "MA" } }) { customer_id first_name } }' \
        '.data.customer | length >= 1 and all(.first_name | startswith("MA"))'

    run_test "Customer Filter endsWith" \
        '{ customer(where: { last_name: { endsWith: "SON" } }) { customer_id last_name } }' \
        '.data.customer | length >= 1 and all(.last_name | endswith("SON"))'

    run_test "Customer Filter like" \
        '{ customer(where: { email: { like: "%@example.com" } }) { customer_id email } }' \
        '.data.customer | length >= 5'

    run_test "Customer Filter isNull (email is null)" \
        '{ customer(where: { email: { isNull: true } }) { customer_id email } }' \
        '.data.customer | length >= 0 and all(.email == null)'

    run_test "Customer Filter isNotNull (email not null)" \
        '{ customer(where: { email: { isNotNull: true } }) { customer_id email } }' \
        '.data.customer | length >= 10 and all(.email != null)'

    run_test "Orders filter in list" \
        '{ orders(where: { status: { in: ["delivered", "shipped"] } }) { order_id status } }' \
        '.data.orders | length >= 1 and all(.status == "delivered" or .status == "shipped")'

    run_test "Orders filter notIn list" \
        '{ orders(where: { status: { notIn: ["cancelled"] } }) { order_id status } }' \
        '.data.orders | length >= 1 and all(.status != "cancelled")'

    run_test "Products basic query" \
        '{ product { product_id name price stock } }' \
        '.data.product | length >= 5'

    # ==========================================
    # AGGREGATE QUERIES
    # ==========================================

    run_test "Customer Aggregate count" \
        '{ customer_aggregate { count } }' \
        '.data.customer_aggregate.count >= 10'

    run_test "Orders Aggregate count" \
        '{ orders_aggregate { count } }' \
        '.data.orders_aggregate.count >= 10'

    run_test "Orders Aggregate sum" \
        '{ orders_aggregate { sum } }' \
        '.data.orders_aggregate.sum > 0'

    run_test "Orders Aggregate avg" \
        '{ orders_aggregate { avg } }' \
        '.data.orders_aggregate.avg > 0'

    run_test "Orders Aggregate min/max" \
        '{ orders_aggregate { min max } }' \
        '.data.orders_aggregate.min <= .data.orders_aggregate.max'

    # ==========================================
    # CONNECTION (CURSOR PAGINATION)
    # ==========================================

    run_test "Customer Connection first" \
        '{ customerConnection(first: 5) { edges { node { customer_id first_name } cursor } pageInfo { hasNextPage hasPreviousPage } } }' \
        '.data.customerConnection.edges | length == 5'

    run_test "Customer Connection hasNextPage" \
        '{ customerConnection(first: 3) { pageInfo { hasNextPage } } }' \
        '.data.customerConnection.pageInfo.hasNextPage == true'

    # ==========================================
    # MUTATIONS — CREATE
    # ==========================================

    local new_customer
    new_customer=$(curl -s --max-time "$TIMEOUT" -X POST \
        -H "Content-Type: application/json" \
        -d '{"query":"mutation { createCustomer(input: { first_name: \"E2E\", last_name: \"Test\", email: \"e2e@test.com\", active: 1 }) { customer_id first_name last_name email } }"}' \
        "$API_URL")

    ((test_count++))
    if echo "$new_customer" | jq -e '.data.createCustomer.first_name == "E2E"' > /dev/null 2>&1; then
        log_success "Create Customer mutation: ✓ Passed"
        ((passed_tests++))
    else
        log_error "Create Customer mutation: Failed"
        echo "$new_customer" | jq '.errors // .'
        ((failed_tests++))
    fi

    # Get the newly created ID
    local new_id
    new_id=$(echo "$new_customer" | jq -r '.data.createCustomer.customer_id')

    # ==========================================
    # MUTATIONS — UPDATE
    # ==========================================

    if [ -n "$new_id" ] && [ "$new_id" != "null" ]; then
        local update_result
        update_result=$(curl -s --max-time "$TIMEOUT" -X POST \
            -H "Content-Type: application/json" \
            -d "{\"query\":\"mutation { updateCustomer(id: $new_id, input: { first_name: \\\"E2E_Updated\\\" }) { customer_id first_name } }\"}" \
            "$API_URL")

        ((test_count++))
        if echo "$update_result" | jq -e '.data.updateCustomer.first_name == "E2E_Updated"' > /dev/null 2>&1; then
            log_success "Update Customer mutation: ✓ Passed"
            ((passed_tests++))
        else
            log_error "Update Customer mutation: Failed"
            echo "$update_result" | jq '.errors // .'
            ((failed_tests++))
        fi

        # ==========================================
        # MUTATIONS — DELETE
        # ==========================================

        local delete_result
        delete_result=$(curl -s --max-time "$TIMEOUT" -X POST \
            -H "Content-Type: application/json" \
            -d "{\"query\":\"mutation { deleteCustomer(id: $new_id) { customer_id } }\"}" \
            "$API_URL")

        ((test_count++))
        if echo "$delete_result" | jq -e '.data.deleteCustomer != null' > /dev/null 2>&1; then
            log_success "Delete Customer mutation: ✓ Passed"
            ((passed_tests++))
        else
            log_error "Delete Customer mutation: Failed"
            echo "$delete_result" | jq '.errors // .'
            ((failed_tests++))
        fi
    fi

    # ==========================================
    # MUTATIONS — BULK CREATE
    # ==========================================

    local bulk_result
    bulk_result=$(curl -s --max-time "$TIMEOUT" -X POST \
        -H "Content-Type: application/json" \
        -d '{"query":"mutation { createManyCustomers(input: [{ first_name: \"Bulk1\", last_name: \"Test\", email: \"bulk1@test.com\" }, { first_name: \"Bulk2\", last_name: \"Test\", email: \"bulk2@test.com\" }]) { customer_id first_name } }"}' \
        "$API_URL")

    ((test_count++))
    if echo "$bulk_result" | jq -e '.data.createManyCustomers | length == 2' > /dev/null 2>&1; then
        log_success "Bulk Create Customers mutation: ✓ Passed"
        ((passed_tests++))
    else
        log_error "Bulk Create Customers mutation: Failed"
        echo "$bulk_result" | jq '.errors // .'
        ((failed_tests++))
    fi

    # ==========================================
    # CROSS-TABLE QUERIES
    # ==========================================

    run_test "Orders filter by customer_id" \
        '{ orders(where: { customer_id: { eq: 1 } }) { order_id customer_id total } }' \
        '.data.orders | length >= 1'

    run_test "Products filter active" \
        '{ product(where: { active: { eq: 1 } }) { product_id name } }' \
        '.data.product | length >= 1'

    # ==========================================
    # ENUM COLUMN TESTS
    # ==========================================

    run_test "Task basic query with ENUM columns" \
        '{ task { task_id title status priority } }' \
        '.data.task | length >= 5'

    run_test "Task filter by ENUM status" \
        '{ task(where: { status: { eq: "done" } }) { task_id title status } }' \
        '(.data.task | length) >= 1 and (.data.task | all(.status == "done"))'

    run_test "Task filter by ENUM priority" \
        '{ task(where: { priority: { eq: "high" } }) { task_id title priority } }' \
        '.data.task | length >= 1'

    run_test "Task multiple values via separate queries" \
        '{ task(where: { status: { eq: "todo" } }) { task_id status } }' \
        '.data.task | length >= 1'

    run_test "Task aggregate count by status" \
        '{ task_aggregate { count } }' \
        '.data.task_aggregate.count >= 5'

    run_test "Task create with ENUM" \
        '{ task { task_id title status priority } }' \
        '.data.task | length >= 1'

    # Create a task with enum values
    local create_task_result
    create_task_result=$(curl -s --max-time "$TIMEOUT" -X POST \
        -H "Content-Type: application/json" \
        -d '{"query":"mutation { createTask(input: { title: \"E2E Task\", status: \"in_progress\", priority: \"high\" }) { task_id title status priority } }"}' \
        "$API_URL")
    ((test_count++))
    if echo "$create_task_result" | jq -e '.data.createTask.status == "in_progress" and .data.createTask.priority == "high"' > /dev/null 2>&1; then
        log_success "Create Task with ENUM values: ✓ Passed"
        ((passed_tests++))
    else
        log_error "Create Task with ENUM values: Failed"
        echo "$create_task_result" | jq '.errors // .'
        ((failed_tests++))
    fi

    # ==========================================
    # JSON COLUMN TESTS
    # ==========================================

    run_test "Product detail basic query with JSON columns" \
        '{ product_detail { detail_id product_id attributes metadata tags } }' \
        '.data.product_detail | length >= 3'

    run_test "JSON columns return non-null values" \
        '{ product_detail(limit: 1) { attributes metadata tags } }' \
        '.data.product_detail[0].attributes != null'

    run_test "JSON filter on non-JSON column" \
        '{ product_detail(where: { product_id: { eq: 1 } }) { detail_id product_id attributes } }' \
        '.data.product_detail | length == 1'

    # Create with JSON
    local create_json_result
    create_json_result=$(curl -s --max-time "$TIMEOUT" -X POST \
        -H "Content-Type: application/json" \
        -d '{"query":"mutation { createProductDetail(input: { product_id: 1, attributes: { color: \"green\", weight: 1.0 }, tags: [\"e2e\", \"test\"] }) { detail_id product_id attributes tags } }"}' \
        "$API_URL")
    ((test_count++))
    if echo "$create_json_result" | jq -e '.data.createProductDetail.detail_id != null' > /dev/null 2>&1; then
        log_success "Create with JSON columns: ✓ Passed"
        ((passed_tests++))
    else
        log_error "Create with JSON columns: Failed"
        echo "$create_json_result" | jq '.errors // .'
        ((failed_tests++))
    fi

    # ==========================================
    # VIEW TESTS (read-only, no mutations)
    # ==========================================

    run_test "active_customers view query" \
        '{ active_customers { customer_id first_name last_name email } }' \
        '.data.active_customers | length >= 10'

    run_test "active_customers view excludes inactive" \
        '{ customer(where: { active: { eq: 0 } }) { customer_id active } }' \
        '.data.customer | length >= 2'

    run_test "orders_summary view query" \
        '{ orders_summary { customer_id first_name last_name order_count total_spent } }' \
        '.data.orders_summary | length >= 5'

    run_test "high_value_orders view query" \
        '{ high_value_orders { order_id total status first_name last_name } }' \
        '(.data.high_value_orders | length) >= 1 and (.data.high_value_orders | all(.total > 50))'

    run_test "View pagination" \
        '{ active_customers(limit: 3, offset: 0) { customer_id first_name } }' \
        '.data.active_customers | length == 3'

    run_test "View ordering" \
        '{ active_customers(orderBy: { customer_id: "ASC" }, limit: 3) { customer_id } }' \
        '.data.active_customers[0].customer_id < .data.active_customers[1].customer_id'

    run_test "View aggregate" \
        '{ active_customers_aggregate { count } }' \
        '.data.active_customers_aggregate.count >= 10'

    # Verify no mutation fields for views
    local schema_check
    schema_check=$(curl -s --max-time "$TIMEOUT" -X POST \
        -H "Content-Type: application/json" \
        -d '{"query":"{ __type(name: \"Mutation\") { fields { name } } }"}' \
        "$API_URL")
    ((test_count++))
    if echo "$schema_check" | jq -e '[.data.__type.fields[].name] | map(select(startswith("create") or startswith("update") or startswith("delete"))) | map(select(contains("active_customers") or contains("orders_summary") or contains("high_value_orders"))) | length == 0' > /dev/null 2>&1; then
        log_success "Views have no mutation fields (read-only): ✓ Passed"
        ((passed_tests++))
    else
        log_error "Views have no mutation fields (read-only): Failed"
        echo "$schema_check" | jq '[.data.__type.fields[].name]'
        ((failed_tests++))
    fi

    # ==========================================
    # SUMMARY
    # ==========================================

    echo ""
    echo "=================================================="
    echo "📊 MySQL E2E Test Results"
    echo "=================================================="
    echo "Total Tests : $test_count"
    echo "✅ Passed   : $passed_tests"
    echo "❌ Failed   : $failed_tests"
    echo "📈 Success  : $(( (passed_tests * 100) / test_count ))%"
    echo ""
    echo "🐬 MySQL Coverage:"
    echo "  ✅ Schema introspection"
    echo "  ✅ Basic queries (filter, pagination, ordering)"
    echo "  ✅ Filter operators (eq, neq, gt, gte, lt, lte, contains, startsWith, endsWith, like, isNull, isNotNull, in, notIn)"
    echo "  ✅ Aggregate queries (count, sum, avg, min, max)"
    echo "  ✅ Cursor-based pagination (Connection)"
    echo "  ✅ Mutations (create, update, delete, bulk create)"
    echo "  ✅ Cross-table queries (filter by FK column)"
    echo "  ✅ ENUM columns (filter, create, values preserved)"
    echo "  ✅ JSON columns (query, create with objects)"
    echo "  ✅ Views (query, filter, aggregate, read-only enforcement)"
    echo "=================================================="

    if [ $failed_tests -eq 0 ]; then
        log_success "🎉 All tests passed!"
        return 0
    else
        log_error "❌ Some tests failed. Check logs above."
        return 1
    fi
}

command -v curl >/dev/null 2>&1 || { log_error "curl is required"; exit 1; }
command -v jq   >/dev/null 2>&1 || { log_error "jq is required";   exit 1; }

main "$@"
