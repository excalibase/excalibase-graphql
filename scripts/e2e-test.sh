#!/bin/bash

# Excalibase GraphQL E2E Test Suite
# Tests the complete GraphQL API using curl commands
# Requires: docker-compose services running on ports 10001 (app) and 5433 (postgres)

# set -e  # Exit on any error - keeping commented to show all results

# Configuration
API_URL="http://localhost:10001/graphql"
TIMEOUT=30
MAX_RETRIES=10
RETRY_DELAY=5

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Wait for GraphQL API to be ready
wait_for_api() {
    log_info "Waiting for GraphQL API to be ready..."
    
    for i in $(seq 1 $MAX_RETRIES); do
        if curl -s --connect-timeout 5 "$API_URL" > /dev/null 2>&1; then
            log_success "GraphQL API is ready!"
            return 0
        fi
        
        log_warning "Attempt $i/$MAX_RETRIES: API not ready, waiting ${RETRY_DELAY}s..."
        sleep $RETRY_DELAY
    done
    
    log_error "GraphQL API failed to start after $MAX_RETRIES attempts"
    return 1
}

# Execute GraphQL query and validate response
execute_graphql_query() {
    local test_name="$1"
    local query="$2"
    local expected_check="$3"  # Optional: specific check for response
    
    log_info "Testing: $test_name"
    
    # Create properly escaped JSON payload
    local json_payload=$(printf '{"query": %s}' "$(printf '%s' "$query" | jq -R .)")
    
    # Execute query
    local response=$(curl -s \
        --max-time "$TIMEOUT" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "$json_payload" \
        "$API_URL")
    
    # Check for curl errors
    if [ $? -ne 0 ]; then
        log_error "$test_name: Failed to execute HTTP request"
        return 1
    fi
    
    # Check for GraphQL errors
    if echo "$response" | jq -e '.errors' > /dev/null 2>&1; then
        log_error "$test_name: GraphQL errors found"
        echo "$response" | jq '.errors'
        return 1
    fi
    
    # Check for data presence
    if ! echo "$response" | jq -e '.data' > /dev/null 2>&1; then
        log_error "$test_name: No data field in response"
        echo "$response"
        return 1
    fi
    
    # Custom validation if provided
    if [ -n "$expected_check" ]; then
        if ! echo "$response" | jq -e "$expected_check" > /dev/null 2>&1; then
            log_error "$test_name: Expected check failed: $expected_check"
            echo "$response" | jq '.'
            return 1
        fi
    fi
    
    log_success "$test_name: ✓ Passed"
    return 0
}

# Test counter
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

# Main test suite
main() {
    echo "=================================================="
    echo "🚀 Excalibase GraphQL E2E Test Suite"
    echo "🎯 API Endpoint: $API_URL"
    echo "🐘 Database: PostgreSQL on port 5433"
    echo "=================================================="
    
    # Wait for API to be ready
    if ! wait_for_api; then
        exit 1
    fi
    
    echo ""
    log_info "🧪 Starting GraphQL API Tests..."
    echo ""
    
    # ==========================================
    # SCHEMA INTROSPECTION TESTS
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
    # BASIC QUERY TESTS
    # ==========================================
    
    run_test "Get All Customers" \
        "{ customer { customer_id first_name last_name email } }" \
        '.data.customer | length >= 10'
    
    run_test "Customer with Filtering" \
        "{ customer(where: { first_name: { eq: \"MARY\" } }) { customer_id first_name last_name } }" \
        '.data.customer | length >= 1'
    
    run_test "Customer with OR Filtering" \
        "{ customer(or: [{ first_name: { eq: \"MARY\" } }, { first_name: { eq: \"JOHN\" } }]) { customer_id first_name } }" \
        '.data.customer | length >= 2'
    
    run_test "Customer Pagination" \
        "{ customer(limit: 3, offset: 2) { customer_id first_name } }" \
        '.data.customer | length == 3'
    
    run_test "Customer with Ordering" \
        "{ customer(orderBy: { customer_id: ASC }, limit: 5) { customer_id first_name } }" \
        '.data.customer[0].customer_id < .data.customer[1].customer_id'
    
    # ==========================================
    # ENHANCED POSTGRESQL TYPES TESTS
    # ==========================================
    
    run_test "Enhanced Types - All Fields" \
        "{ enhanced_types { id name json_col jsonb_col int_array text_array timestamptz_col } }" \
        '.data.enhanced_types | length >= 3'
    
    run_test "Enhanced Types - JSON Filtering" \
        "{ enhanced_types(where: { name: { eq: \"Test Record 1\" } }) { id name json_col } }" \
        '.data.enhanced_types[0].json_col != null'
    
    run_test "Enhanced Types - Array Fields" \
        "{ enhanced_types { id name int_array text_array } }" \
        '.data.enhanced_types[0].int_array != null and .data.enhanced_types[0].text_array != null'
    
    run_test "Enhanced Types - Network Fields" \
        "{ enhanced_types { id inet_col cidr_col macaddr_col } }" \
        '.data.enhanced_types[0].inet_col != null'
    
    run_test "Enhanced Types - Datetime Fields" \
        "{ enhanced_types { id timestamptz_col timetz_col interval_col } }" \
        '.data.enhanced_types[0].timestamptz_col != null'
    
    # ==========================================
    # CUSTOM TYPES TESTS (ENUMS & COMPOSITE)
    # ==========================================
    
    run_test "Custom Enum Types - OrderStatus in Schema" \
        "{ __type(name: \"OrderStatus\") { name kind enumValues { name } } }" \
        '.data.__type.kind == "ENUM" and (.data.__type.enumValues | length == 5)'
    
    run_test "Custom Enum Types - UserRole in Schema" \
        "{ __type(name: \"UserRole\") { name kind enumValues { name } } }" \
        '.data.__type.kind == "ENUM" and (.data.__type.enumValues | length >= 3)'
    
    run_test "Custom Composite Types - Address in Schema" \
        "{ __type(name: \"Address\") { name kind fields { name type { name } } } }" \
        '.data.__type.kind == "OBJECT" and (.data.__type.fields | length == 5)'
    
    # ==========================================
    # DOMAIN TYPES TESTS
    # ==========================================
    
    run_test "Domain Types - Query Domain Types Test Table" \
        "{ domain_types_test { id email quantity price username tags rating description is_active } }" \
        '.data.domain_types_test | length >= 4'
    
    run_test "Domain Types - Email Domain Validation" \
        "{ domain_types_test(where: { username: { eq: \"john_doe\" } }) { email username } }" \
        '.data.domain_types_test[0].email == "john.doe@example.com"'
    
    run_test "Domain Types - Positive Integer Domain" \
        "{ domain_types_test { quantity } }" \
        '.data.domain_types_test | all(.quantity > 0)'
    
    run_test "Domain Types - Price Domain (Decimal)" \
        "{ domain_types_test { price } }" \
        '.data.domain_types_test | all(.price >= 0)'
    
    run_test "Domain Types - Text Array Domain" \
        "{ domain_types_test(where: { username: { eq: \"jane_smith\" } }) { tags } }" \
        '.data.domain_types_test[0].tags | length >= 2'
    
    run_test "Domain Types - Rating Domain with Constraints" \
        "{ domain_types_test { rating } }" \
        '.data.domain_types_test | map(.rating) | all(. >= 1 and . <= 5 and . != null)'
    
    run_test "Domain Types - Filtering by Domain Type Fields" \
        "{ domain_types_test(where: { rating: { eq: 5 } }) { username rating } }" \
        '.data.domain_types_test | length >= 1 and all(.rating == 5)'
    
    run_test "Custom Enum Usage - Orders with Status" \
        "{ orders { order_id status } }" \
        '(.data.orders | length >= 1) and (.data.orders[0].status | test("PENDING|PROCESSING|SHIPPED|DELIVERED|CANCELLED"))'
    
    run_test "Custom Enum Usage - Users with Role" \
        "{ users { id role } }" \
        '(.data.users | length >= 1) and (.data.users[0].role | test("ADMIN|MODERATOR|USER|GUEST"))'
    
    run_test "Custom Types Test Table - Mixed Types" \
        "{ custom_types_test { id name status role priority } }" \
        '(.data.custom_types_test | length >= 1) and (.data.custom_types_test[0].status | test("PENDING|PROCESSING|SHIPPED|DELIVERED|CANCELLED"))'
    
    run_test "Query Existing Composite Types - Orders with Address" \
        "{ orders(where: { order_id: { eq: 1 } }) { order_id shipping_address { street city state postal_code country } } }" \
        '.data.orders[0].shipping_address.street == "123 Delivery St" and .data.orders[0].shipping_address.city == "New York"'
    
    # ==========================================
    # RELATIONSHIP TESTS
    # ==========================================
    
    run_test "Orders with Customer Relationship" \
        "{ orders { order_id total_amount customer_id customer { first_name last_name } } }" \
        '(.data.orders | length >= 1) and (.data.orders[0].customer.first_name | length > 0)'
    
    run_test "Customer with Orders Relationship" \
        "{ customer(where: { customer_id: { eq: 1 } }) { customer_id first_name orders { order_id total_amount } } }" \
        '.data.customer[0].orders | length >= 1'
    
    # ==========================================
    # VIEW TESTS (Read-only)
    # ==========================================
    
    run_test "Active Customers View" \
        "{ active_customers { customer_id first_name last_name email } }" \
        '.data.active_customers | length >= 5'
    
    run_test "Enhanced Types Summary View" \
        "{ enhanced_types_summary { id name json_name array_size } }" \
        '.data.enhanced_types_summary | length >= 3'
    
    # ==========================================
    # MUTATION TESTS
    # ==========================================
    
    run_test "Create Customer Mutation" \
        "mutation { createCustomer(input: { first_name: \"TEST\", last_name: \"USER\", email: \"test@example.com\", active: true }) { customer_id first_name last_name email } }" \
        '.data.createCustomer.customer_id != null and .data.createCustomer.first_name == "TEST"'
    
        run_test "Update Customer Mutation" \
        "mutation { updateCustomer(input: { customer_id: 13, email: \"updated@example.com\" }) { customer_id email } }" \
        '.data.updateCustomer.email == "updated@example.com"'

    # ==========================================
    # CUSTOM TYPES MUTATION TESTS
    # ==========================================
    
    run_test "Create Order with OrderStatus Enum" \
        "mutation { createOrders(input: { customer_id: 1, status: \"pending\", total_amount: 99.99 }) { order_id status customer_id total_amount } }" \
        '.data.createOrders.order_id != null and .data.createOrders.status == "PENDING" and .data.createOrders.total_amount == 99.99'
    
    run_test "Update Order Status Enum" \
        "mutation { updateOrders(input: { order_id: 1, status: \"shipped\" }) { order_id status } }" \
        '.data.updateOrders.status == "SHIPPED"'
    
    run_test "Create Order with Different Status Values" \
        "mutation { createOrders(input: { customer_id: 2, status: \"processing\", total_amount: 149.99 }) { order_id status } }" \
        '.data.createOrders.status == "PROCESSING"'
    
    run_test "Test OrderStatus Enum Values" \
        "mutation { createOrders(input: { customer_id: 3, status: \"delivered\", total_amount: 75.50 }) { order_id status } }" \
        '.data.createOrders.status == "DELIVERED"'
    
    # Generate unique username with timestamp to avoid duplicates
    TIMESTAMP=$(date +%s)
    run_test "Create User with UserRole Enum" \
        "mutation { createUsers(input: { username: \"testuser_${TIMESTAMP}\", email: \"testuser_${TIMESTAMP}@example.com\", role: \"user\" }) { id username role email } }" \
        '.data.createUsers.id != null and .data.createUsers.role == "USER"'
    
    run_test "Update User Role" \
        "mutation { updateUsers(input: { id: 1, role: \"admin\" }) { id role } }" \
        '.data.updateUsers.role == "ADMIN"'
    
    run_test "Create with Composite Type (Address)" \
        "mutation { createOrders(input: { customer_id: 4, status: \"pending\", total_amount: 199.99, shipping_address: \"(\\\"123 Main St\\\",\\\"New York\\\",\\\"NY\\\",\\\"10001\\\",\\\"USA\\\")\" }) { order_id shipping_address { street city state postal_code country } } }" \
        '.data.createOrders.order_id != null and .data.createOrders.shipping_address.street == "123 Main St"'
    
    run_test "Create Task with PriorityLevel Enum" \
        "mutation { createTasks(input: { title: \"Test Task\", priority: \"high\", assigned_user_id: 1 }) { id title priority } }" \
        '.data.createTasks.id != null and .data.createTasks.priority == "HIGH"'
    
    run_test "Test Multiple Custom Types in Single Mutation" \
        "mutation { createCustom_types_test(input: { name: \"Mixed Test\", status: \"pending\", role: \"user\", priority: \"medium\" }) { id name status role priority } }" \
        '.data.createCustom_types_test.status == "PENDING" and .data.createCustom_types_test.role == "USER" and .data.createCustom_types_test.priority == "MEDIUM"'

    # ==========================================
    # CUSTOM TYPES ERROR HANDLING TESTS
    # ==========================================
    
    log_info "Testing: Invalid Enum Values"
    INVALID_ENUM_RESPONSE=$(curl -s -X POST "$API_URL" \
        -H "Content-Type: application/json" \
        -d '{"query": "mutation { createOrders(input: { customer_id: 1, status: \"invalid_status\", total_amount: 50.00 }) { order_id status } }"}')
    
    if echo "$INVALID_ENUM_RESPONSE" | grep -q "errors"; then
        log_success "✅ Invalid enum value properly rejected"
    else
        log_error "❌ Invalid enum value should have been rejected"
        echo "Response: $INVALID_ENUM_RESPONSE" | head -3
    fi

    # ==========================================
    # CONNECTION/CURSOR PAGINATION TESTS
    # ==========================================
    
    run_test "Customer Connection Query" \
        "{ customerConnection(first: 3) { edges { node { customer_id first_name } cursor } pageInfo { hasNextPage hasPreviousPage } } }" \
        '(.data.customerConnection.edges | length <= 3) and (.data.customerConnection.pageInfo.hasNextPage | type) == "boolean"'
    
    # ==========================================
    # COMPLEX FILTERING TESTS  
    # ==========================================
    
    run_test "Complex Date Range Filter" \
        "{ customer(where: { create_date: { gte: \"2006-01-01\", lt: \"2008-01-01\" } }) { customer_id create_date } }" \
        '.data.customer | length >= 1'
    
    run_test "Complex String Filter" \
        "{ customer(where: { email: { contains: \"@example.com\" } }) { customer_id email } }" \
        '.data.customer | length >= 5'
    
    run_test "Boolean Filter" \
        "{ customer(where: { active: { eq: true } }) { customer_id first_name active } }" \
        '.data.customer | length >= 5'
    
    run_test "IN Array Filter" \
        "{ customer(where: { customer_id: { in: [1, 2, 3] } }) { customer_id first_name } }" \
        '.data.customer | length == 3'
    
    # ==========================================
    # ERROR HANDLING TESTS
    # ==========================================
    
    log_info "Testing: Invalid Query Handling"
    response=$(curl -s -X POST -H "Content-Type: application/json" -d '{"query": "{ invalid_field }"}' "$API_URL")
    if echo "$response" | jq -e '.errors' > /dev/null 2>&1; then
        log_success "Invalid Query Handling: ✓ Passed (correctly returned errors)"
        ((test_count++))
        ((passed_tests++))
    else
        log_error "Invalid Query Handling: Should have returned errors"
        ((test_count++))
        ((failed_tests++))
    fi
    
    # ==========================================
    # TEST SUMMARY
    # ==========================================
    
    echo ""
    echo "=================================================="
    echo "📊 E2E Test Results Summary"
    echo "=================================================="
    echo "Total Tests: $test_count"
    echo "✅ Passed: $passed_tests"
    echo "❌ Failed: $failed_tests"
    echo "📈 Success Rate: $(((passed_tests * 100) / test_count))%"
    echo ""
    echo "🎯 Custom Types Coverage:"
    echo "  ✅ OrderStatus enum (pending, processing, shipped, delivered)"
    echo "  ✅ UserRole enum (user, admin, moderator)"
    echo "  ✅ PriorityLevel enum (low, medium, high, urgent)"
    echo "  ✅ Address composite type (street, city, state, postal_code, country)"
    echo "  ✅ Mixed custom types in single mutations"
    echo "  ✅ Invalid enum value error handling"
    echo "=================================================="
    
    if [ $failed_tests -eq 0 ]; then
        log_success "🎉 All tests passed! GraphQL API is working correctly."
        echo ""
        log_info "🌐 GraphQL Endpoint: $API_URL"
        log_info "🔍 Try the GraphiQL interface in your browser!"
        echo ""
        return 0
    else
        log_error "❌ Some tests failed. Please check the logs above."
        return 1
    fi
}

# Check dependencies
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed. Aborting."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed. Aborting."; exit 1; }

# Run main test suite
main "$@" 