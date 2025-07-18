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
    # PERFORMANCE TESTS
    # ==========================================
    
    log_info "Testing: Response Time Performance"
    start_time=$(date +%s%N)
    
    response=$(curl -s -X POST -H "Content-Type: application/json" \
        -d '{"query": "{ customer(limit: 100) { customer_id first_name last_name email create_date } }"}' \
        "$API_URL")
    
    end_time=$(date +%s%N)
    response_time=$(((end_time - start_time) / 1000000))  # Convert to milliseconds
    
    if [ $response_time -lt 1000 ]; then
        log_success "Performance Test: ✓ Passed (${response_time}ms < 1000ms)"
        ((test_count++))
        ((passed_tests++))
    else
        log_warning "Performance Test: Slow response (${response_time}ms >= 1000ms)"
        ((test_count++))
        ((passed_tests++))  # Don't fail on performance, just warn
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