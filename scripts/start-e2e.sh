#!/bin/bash

# Excalibase GraphQL E2E Test Runner
# Complete end-to-end testing with Docker Compose
# Usage: ./scripts/start-e2e.sh [--skip-build] [--no-cleanup] [--verbose]

set -e  # Exit on any error

# Configuration
COMPOSE_FILE="docker-compose.yml"
APP_PORT=10001
DB_PORT=5433
PROJECT_NAME="excalibase-e2e"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Command line options
SKIP_BUILD=false
NO_CLEANUP=false
VERBOSE=false

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --no-cleanup)
            NO_CLEANUP=true
            shift
            ;;
        --verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--skip-build] [--no-cleanup] [--verbose]"
            echo ""
            echo "Options:"
            echo "  --skip-build    Skip Maven build step"
            echo "  --no-cleanup    Don't cleanup containers after tests"
            echo "  --verbose       Show detailed docker-compose logs"
            echo "  -h, --help      Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use -h or --help for usage information"
            exit 1
            ;;
    esac
done

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

log_step() {
    echo -e "${CYAN}[STEP]${NC} $1"
}

# Cleanup function
cleanup() {
    if [ "$NO_CLEANUP" = false ]; then
        log_step "🧹 Cleaning up containers..."
        docker-compose -p "$PROJECT_NAME" down -v --remove-orphans || true
        log_success "Cleanup completed"
    else
        log_warning "Skipping cleanup as requested"
        log_info "To cleanup manually: docker-compose -p $PROJECT_NAME down -v"
    fi
}

# Set up cleanup trap
trap cleanup EXIT

# Check for required tools
check_dependencies() {
    log_step "🔍 Checking dependencies..."
    
    local missing_deps=()
    
    if ! command -v docker >/dev/null 2>&1; then
        missing_deps+=("docker")
    fi
    
    if ! command -v docker-compose >/dev/null 2>&1; then
        missing_deps+=("docker-compose")
    fi
    
    if ! command -v curl >/dev/null 2>&1; then
        missing_deps+=("curl")
    fi
    
    if ! command -v jq >/dev/null 2>&1; then
        missing_deps+=("jq")
    fi
    
    if ! command -v mvn >/dev/null 2>&1 && [ "$SKIP_BUILD" = false ]; then
        missing_deps+=("maven")
    fi
    
    if [ ${#missing_deps[@]} -ne 0 ]; then
        log_error "Missing required dependencies: ${missing_deps[*]}"
        echo ""
        echo "Please install the missing dependencies:"
        for dep in "${missing_deps[@]}"; do
            case $dep in
                docker)
                    echo "  - Docker: https://docs.docker.com/get-docker/"
                    ;;
                docker-compose)
                    echo "  - Docker Compose: https://docs.docker.com/compose/install/"
                    ;;
                curl)
                    echo "  - curl: Usually pre-installed or available via package manager"
                    ;;
                jq)
                    echo "  - jq: https://stedolan.github.io/jq/download/"
                    ;;
                maven)
                    echo "  - Maven: https://maven.apache.org/install.html"
                    ;;
            esac
        done
        exit 1
    fi
    
    log_success "All dependencies are available"
}

# Check if ports are available
check_ports() {
    log_step "🔍 Checking port availability..."
    
    local busy_ports=()
    
    if lsof -i :$APP_PORT >/dev/null 2>&1; then
        busy_ports+=("$APP_PORT")
    fi
    
    if lsof -i :$DB_PORT >/dev/null 2>&1; then
        busy_ports+=("$DB_PORT")
    fi
    
    if [ ${#busy_ports[@]} -ne 0 ]; then
        log_error "Required ports are already in use: ${busy_ports[*]}"
        echo ""
        echo "Please stop the services using these ports or modify docker-compose.yml"
        echo "You can check what's using a port with: lsof -i :PORT_NUMBER"
        exit 1
    fi
    
    log_success "Required ports ($APP_PORT, $DB_PORT) are available"
}

# Build the application
build_application() {
    if [ "$SKIP_BUILD" = true ]; then
        log_warning "Skipping Maven build as requested"
        return 0
    fi
    
    log_step "🔨 Building application with Maven..."
    
    # Clean and build
    if ! mvn clean package -DskipTests; then
        log_error "Maven build failed"
        exit 1
    fi
    
    log_success "Application built successfully"
}

# Start services with docker-compose
start_services() {
    log_step "🚀 Starting services with Docker Compose..."
    
    # Clean up any existing containers
    docker-compose -p "$PROJECT_NAME" down -v --remove-orphans >/dev/null 2>&1 || true
    
    # Start services
    if [ "$VERBOSE" = true ]; then
        docker-compose -p "$PROJECT_NAME" up -d --build
    else
        docker-compose -p "$PROJECT_NAME" up -d --build >/dev/null 2>&1
    fi
    
    log_success "Services started successfully"
    
    # Show service status
    log_info "Service status:"
    docker-compose -p "$PROJECT_NAME" ps
}

# Wait for services to be healthy
wait_for_services() {
    log_step "⏳ Waiting for services to be ready..."
    
    # Wait for PostgreSQL
    log_info "Waiting for PostgreSQL..."
    local max_attempts=30
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        if docker-compose -p "$PROJECT_NAME" exec -T postgres pg_isready -U excalibase_user -d excalibase_e2e >/dev/null 2>&1; then
            log_success "PostgreSQL is ready!"
            break
        fi
        
        if [ $attempt -eq $max_attempts ]; then
            log_error "PostgreSQL failed to start after $max_attempts attempts"
            log_info "Checking PostgreSQL logs:"
            docker-compose -p "$PROJECT_NAME" logs postgres
            exit 1
        fi
        
        echo -n "."
        sleep 2
        ((attempt++))
    done
    
    # Give the app a bit more time to start
    log_info "Waiting for application to start..."
    sleep 10
    
    log_success "All services are ready"
}

# Run the e2e tests
run_tests() {
    log_step "🧪 Running E2E Tests..."
    
    if ! ./scripts/e2e-test.sh; then
        log_error "E2E tests failed"
        
        if [ "$VERBOSE" = true ]; then
            log_info "Application logs:"
            docker-compose -p "$PROJECT_NAME" logs app
        fi
        
        exit 1
    fi
    
    log_success "All E2E tests passed!"
}

# Show useful information
show_info() {
    echo ""
    echo "=================================================="
    echo "🎉 Excalibase GraphQL E2E Testing Complete!"
    echo "=================================================="
    echo ""
    echo "🌐 Services are running on:"
    echo "   • GraphQL API: http://localhost:$APP_PORT/graphql"
    echo "   • PostgreSQL:  localhost:$DB_PORT"
    echo ""
    echo "🔍 You can:"
    echo "   • Visit http://localhost:$APP_PORT/graphql in your browser"
    echo "   • Run additional manual tests"
    echo "   • Check service logs: docker-compose -p $PROJECT_NAME logs -f"
    echo ""
    
    if [ "$NO_CLEANUP" = true ]; then
        echo "🧹 To cleanup when done:"
        echo "   docker-compose -p $PROJECT_NAME down -v"
        echo ""
    fi
}

# Main execution
main() {
    echo "=================================================="
    echo "🚀 Excalibase GraphQL E2E Test Runner"
    echo "🎯 Ports: App=$APP_PORT, DB=$DB_PORT"
    echo "📁 Project: $PROJECT_NAME"
    echo "=================================================="
    echo ""
    
    # Pre-flight checks
    check_dependencies
    check_ports
    
    # Build and start
    build_application
    start_services
    wait_for_services
    
    # Run tests
    run_tests
    
    # Show final info
    show_info
    
    log_success "E2E testing completed successfully! 🎉"
}

# Check if we're in the right directory
if [ ! -f "$COMPOSE_FILE" ]; then
    log_error "docker-compose.yml not found. Please run this script from the project root."
    exit 1
fi

# Run the main function
main "$@" 