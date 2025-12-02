#!/bin/bash

# Sequential Benchmark Runner for Spring Boot
# Runs benchmarks and generates reports

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
PURPLE='\033[0;35m'
NC='\033[0m'

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_section() {
    echo ""
    echo -e "${PURPLE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${PURPLE}$1${NC}"
    echo -e "${PURPLE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
}

# Configuration
COMPOSE_FILE="scripts/docker-compose.benchmark.yml"
PROJECT_NAME="excalibase-benchmark"
BENCHMARK_TYPE="${1:-quick}"  # quick, full, or custom
RESULTS_DIR="scripts/results"

# Ensure results directory exists
mkdir -p "$RESULTS_DIR"

# Check dependencies
check_deps() {
    log_info "Checking dependencies..."

    if ! command -v docker-compose &> /dev/null; then
        log_error "docker-compose not found"
        exit 1
    fi

    if ! command -v k6 &> /dev/null; then
        log_error "k6 not found (brew install k6)"
        exit 1
    fi

    if ! command -v jq &> /dev/null; then
        log_error "jq not found (brew install jq)"
        exit 1
    fi

    log_success "All dependencies available"
}

# Start database (shared by both frameworks)
start_database() {
    log_section "📦 Starting Shared Database"

    # Stop any existing services
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" down > /dev/null 2>&1 || true

    # Start only postgres
    log_info "Starting PostgreSQL with benchmark data..."
    log_warning "Database initialization takes 2-5 minutes for 16.7M records"
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" up -d postgres

    # Wait for database to be ready
    log_info "Waiting for database initialization..."
    local retries=0
    local max_retries=60

    while [ $retries -lt $max_retries ]; do
        if docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" exec -T postgres \
            psql -U excalibase_user -d excalibase_benchmark \
            -c "SELECT status FROM hana.initialization_complete;" 2>/dev/null | grep -q "ENTERPRISE_READY"; then
            log_success "Database ready with enterprise dataset!"
            return 0
        fi

        retries=$((retries + 1))
        if [ $((retries % 12)) -eq 0 ]; then
            log_info "Still waiting... ($((retries / 12)) minutes elapsed)"
        else
            printf "."
        fi
        sleep 5
    done

    log_error "Database failed to initialize"
    return 1
}

# Benchmark a framework
benchmark_framework() {
    local framework=$1
    local profile=$1

    log_section "🚀 Benchmarking ${framework^^}"

    # Start application
    log_info "Starting $framework application..."
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" --profile "$profile" up -d

    # Wait for application to be ready
    log_info "Waiting for application startup..."
    local retries=0
    local max_retries=60

    while [ $retries -lt $max_retries ]; do
        if curl -s --connect-timeout 2 http://localhost:10002/health > /dev/null 2>&1; then
            log_success "$framework application ready!"
            break
        fi

        retries=$((retries + 1))
        if [ $retries -eq $max_retries ]; then
            log_error "$framework application failed to start"
            docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" logs app-$profile
            return 1
        fi

        printf "."
        sleep 2
    done

    echo ""

    # Additional warmup time
    log_info "Warming up application (30s)..."
    sleep 30

    # Run benchmark
    log_info "Running $BENCHMARK_TYPE benchmark for $framework..."

    case $BENCHMARK_TYPE in
        quick)
            FRAMEWORK_NAME="$framework" ./scripts/run-k6-benchmark.sh quick
            ;;
        full)
            FRAMEWORK_NAME="$framework" ./scripts/run-k6-benchmark.sh full
            ;;
        custom)
            local vus=${2:-50}
            local duration=${3:-2m}
            FRAMEWORK_NAME="$framework" ./scripts/run-k6-benchmark.sh custom "$vus" "$duration"
            ;;
        *)
            log_error "Unknown benchmark type: $BENCHMARK_TYPE"
            return 1
            ;;
    esac

    # Stop application (keep database running)
    log_info "Stopping $framework application..."
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" stop app-$profile
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" rm -f app-$profile

    log_success "$framework benchmark complete!"

    # Small cooldown between frameworks
    log_info "Cooldown period (10s)..."
    sleep 10
}

# Cleanup
cleanup() {
    log_section "🧹 Cleanup"

    log_info "Stopping all services..."
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" down -v > /dev/null 2>&1 || true

    log_success "Cleanup complete"
}

# Main
main() {
    echo ""
    echo "================================================================"
    echo "🚀 SPRING BOOT BENCHMARK"
    echo "================================================================"
    echo ""

    log_info "Benchmark Type: $BENCHMARK_TYPE"
    echo ""

    # Check dependencies
    check_deps

    # Start shared database
    if ! start_database; then
        log_error "Failed to start database"
        exit 1
    fi

    # Benchmark Spring Boot
    if ! benchmark_framework "spring"; then
        log_error "Spring Boot benchmark failed"
        cleanup
        exit 1
    fi

    # Cleanup
    cleanup

    echo ""
    echo "================================================================"
    log_success "🎉 Benchmark Complete!"
    echo "================================================================"
    echo ""
    echo "Results saved to: $RESULTS_DIR/spring-*"
    echo ""
}

# Handle interruption
trap cleanup EXIT INT TERM

# Run main
main "$@"
