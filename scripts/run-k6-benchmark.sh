#!/bin/bash

# K6 GraphQL Benchmark Runner
# Runs load tests and generates reports

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

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

# Configuration
BASE_URL="${BASE_URL:-http://localhost:10002}"
RESULTS_DIR="scripts/results"
K6_SCRIPT="scripts/k6-benchmark.js"
FRAMEWORK_NAME="${FRAMEWORK_NAME:-spring}"  # Default to 'spring'

# Check if K6 is installed
check_k6() {
    if ! command -v k6 &> /dev/null; then
        log_error "K6 is not installed"
        echo ""
        echo "Install K6:"
        echo "  macOS:   brew install k6"
        echo "  Linux:   sudo gpg -k && sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69 && echo 'deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main' | sudo tee /etc/apt/sources.list.d/k6.list && sudo apt-get update && sudo apt-get install k6"
        echo "  Windows: choco install k6"
        echo ""
        exit 1
    fi

    log_success "K6 is installed ($(k6 version | head -1))"
}

# Check if server is running
check_server() {
    log_info "Checking if GraphQL server is running at $BASE_URL..."

    # Try Spring Boot actuator health endpoint
    if curl -s --connect-timeout 5 "${BASE_URL}/actuator/health" > /dev/null 2>&1; then
        log_success "Server is running"
        return 0
    fi

    log_error "Server is not running at $BASE_URL"
    echo ""
    echo "Start the server first:"
    echo "  Development: mvn spring-boot:run -pl modules/excalibase-graphql-api"
    echo "  Benchmark:   make benchmark-up"
    echo ""
    exit 1
}

# Create results directory
setup_results_dir() {
    mkdir -p "$RESULTS_DIR"
    log_info "Results will be saved to: $RESULTS_DIR"
}

# Run quick test (30 seconds)
run_quick_test() {
    log_info "Running quick benchmark (30 seconds) [Framework: $FRAMEWORK_NAME]..."
    log_warning "Note: Quick test uses simple scenario (thresholds may not apply)"

    local output_file="$RESULTS_DIR/${FRAMEWORK_NAME}-quick-test.json"

    k6 run \
        --vus 10 \
        --duration 30s \
        --out json="$output_file" \
        -e BASE_URL="$BASE_URL" \
        -e FRAMEWORK_NAME="$FRAMEWORK_NAME" \
        "$K6_SCRIPT"

    log_info ""
    log_info "💡 Tip: Run 'full' benchmark for complete performance profile with all phases"
    log_info "📊 Results saved to: $output_file"
}

# Run full benchmark
run_full_benchmark() {
    log_info "Running full benchmark (~15 minutes) [Framework: $FRAMEWORK_NAME]..."
    log_info "Phases: warmup → ramp_up → sustained → spike"

    k6 run \
        -e BASE_URL="$BASE_URL" \
        -e FRAMEWORK_NAME="$FRAMEWORK_NAME" \
        "$K6_SCRIPT"

    log_info "📊 Results saved with framework tag: $FRAMEWORK_NAME"
}

# Run custom test
run_custom_test() {
    local vus=$1
    local duration=$2

    log_info "Running custom test: ${vus} VUs for ${duration} [Framework: $FRAMEWORK_NAME]..."

    local output_file="$RESULTS_DIR/${FRAMEWORK_NAME}-custom-test.json"

    k6 run \
        --vus "$vus" \
        --duration "$duration" \
        --out json="$output_file" \
        -e BASE_URL="$BASE_URL" \
        -e FRAMEWORK_NAME="$FRAMEWORK_NAME" \
        "$K6_SCRIPT"

    log_info "📊 Results saved to: $output_file"
}

# Run stress test to find limits
run_stress_test() {
    log_info "Running stress test to find performance limits [Framework: $FRAMEWORK_NAME]..."

    local output_file="$RESULTS_DIR/${FRAMEWORK_NAME}-stress-test.json"

    k6 run \
        --stage 30s:50 \
        --stage 1m:100 \
        --stage 1m:200 \
        --stage 1m:300 \
        --stage 1m:400 \
        --stage 1m:500 \
        --stage 30s:0 \
        --out json="$output_file" \
        -e BASE_URL="$BASE_URL" \
        -e FRAMEWORK_NAME="$FRAMEWORK_NAME" \
        "$K6_SCRIPT"

    log_info "📊 Results saved to: $output_file"
}

# Show results
show_results() {
    if [ -f "$RESULTS_DIR/latest.json" ]; then
        log_info "Latest results saved to: $RESULTS_DIR/latest.json"

        # Parse and display key metrics
        if command -v jq &> /dev/null; then
            echo ""
            log_info "Key Metrics:"

            local rps=$(jq -r '.metrics.http_reqs.values.rate' "$RESULTS_DIR/latest.json" 2>/dev/null)
            local p95=$(jq -r '.metrics.http_req_duration.values["p(95)"]' "$RESULTS_DIR/latest.json" 2>/dev/null)
            local p99=$(jq -r '.metrics.http_req_duration.values["p(99)"]' "$RESULTS_DIR/latest.json" 2>/dev/null)
            local error_rate=$(jq -r '.metrics.http_req_failed.values.rate' "$RESULTS_DIR/latest.json" 2>/dev/null)

            if [ "$rps" != "null" ]; then
                echo "  📊 Throughput:  ${rps} req/s"
                echo "  ⚡ p95 Latency: ${p95}ms"
                echo "  🐌 p99 Latency: ${p99}ms"
                echo "  ❌ Error Rate:  $(echo "$error_rate * 100" | bc -l | xargs printf "%.2f")%"
            fi
        fi
    fi
}

# Monitor server resources during test
monitor_resources() {
    log_info "Monitoring server resources (press Ctrl+C to stop)..."

    echo ""
    echo "Timestamp,Memory(MB),CPU(%),Threads"

    while true; do
        local timestamp=$(date +%s)

        # Try to get JVM metrics from Spring Boot actuator
        local jvm_mem=$(curl -s "http://localhost:10002/actuator/metrics/jvm.memory.used" 2>/dev/null | jq -r '.measurements[0].value // 0' | awk '{printf "%.0f", $1/1024/1024}')
        local threads=$(curl -s "http://localhost:10002/actuator/metrics/jvm.threads.live" 2>/dev/null | jq -r '.measurements[0].value // 0')

        # Get CPU usage (rough estimate)
        local cpu="N/A"
        if command -v ps &> /dev/null; then
            local pid=$(pgrep -f "excalibase" | head -1)
            if [ -n "$pid" ]; then
                cpu=$(ps -p "$pid" -o %cpu= 2>/dev/null || echo "N/A")
            fi
        fi

        echo "$timestamp,$jvm_mem,$cpu,$threads"
        sleep 5
    done
}

# Show usage
usage() {
    echo "Usage: $0 [command] [options]"
    echo ""
    echo "Commands:"
    echo "  quick              Run quick 30-second test"
    echo "  full               Run full benchmark with all phases (~15 min)"
    echo "  stress             Run stress test to find limits"
    echo "  custom VUS DURATION Run custom test (e.g., custom 100 5m)"
    echo "  monitor            Monitor server resources in real-time"
    echo ""
    echo "Environment Variables:"
    echo "  BASE_URL           Server URL (default: http://localhost:10002)"
    echo "  FRAMEWORK_NAME     Framework name for result tagging (default: spring)"
    echo ""
    echo "Examples:"
    echo "  $0 quick                                          # Quick 30s test"
    echo "  $0 full                                           # Full benchmark"
    echo "  $0 custom 50 2m                                  # 50 VUs for 2 minutes"
    echo "  BASE_URL=http://localhost:8080 $0 quick          # Test different server"
    echo ""
}

# Main
main() {
    echo ""
    echo "================================================================"
    echo "🚀 K6 GraphQL Benchmark"
    echo "================================================================"
    echo ""

    check_k6
    setup_results_dir

    local command=${1:-help}

    case $command in
        quick)
            check_server
            run_quick_test
            show_results
            ;;
        full)
            check_server
            run_full_benchmark
            show_results
            ;;
        stress)
            check_server
            run_stress_test
            show_results
            ;;
        custom)
            if [ -z "$2" ] || [ -z "$3" ]; then
                log_error "Usage: $0 custom VUS DURATION"
                exit 1
            fi
            check_server
            run_custom_test "$2" "$3"
            show_results
            ;;
        monitor)
            check_server
            monitor_resources
            ;;
        help|--help|-h)
            usage
            ;;
        *)
            log_error "Unknown command: $command"
            echo ""
            usage
            exit 1
            ;;
    esac

    echo ""
    echo "================================================================"
    log_success "Benchmark complete!"
    echo "================================================================"
    echo ""
}

main "$@"
