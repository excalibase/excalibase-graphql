# Excalibase GraphQL Makefile
# Provides e2e testing and development commands

# Configuration
COMPOSE_PROJECT = excalibase-e2e
COMPOSE_TEST_FILE = scripts/docker-compose.test.yml
APP_PORT = 10001
DB_PORT = 5433
API_URL = http://localhost:$(APP_PORT)/graphql

# Colors for output
BLUE = \033[0;34m
GREEN = \033[0;32m
YELLOW = \033[1;33m
RED = \033[0;31m
NC = \033[0m # No Color

# Default target
.DEFAULT_GOAL := help

# Help target
.PHONY: help
help: ## Show this help message
	@echo "$(BLUE)Excalibase GraphQL - Available Commands$(NC)"
	@echo ""
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  $(GREEN)%-15s$(NC) %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""
	@echo "$(YELLOW)Examples:$(NC)"
	@echo "  make e2e            # Complete e2e test (build + test + cleanup)"
	@echo "  make dev            # Start services and keep running"
	@echo "  make test-only      # Run tests against running services"
	@echo "  make clean          # Stop services and cleanup"
	@echo ""
	@echo "$(YELLOW)Enterprise Benchmarking:$(NC)"
	@echo "  make benchmark      # Complete enterprise benchmark (16.7M+ records)"
	@echo "  make benchmark-dev  # Start enterprise benchmark environment"
	@echo "  make benchmark-test # Run enterprise benchmark tests only"
	@echo ""
	@echo "$(YELLOW)Enterprise Debugging:$(NC)"
	@echo "  make benchmark-logs             # Show service logs"
	@echo "  make benchmark-db-shell         # Connect to database"
	@echo "  make benchmark-db-stats         # Show database statistics"

# Main targets
.PHONY: e2e
e2e: check-deps down build up test clean ## Complete e2e test suite (cleanup, build, test, cleanup)
	@echo "$(GREEN)🎉 E2E testing completed successfully!$(NC)"

.PHONY: dev
dev: check-deps build up ## Start services for development (no cleanup)
	@echo ""
	@echo "$(GREEN)🚀 Development environment ready!$(NC)"
	@echo ""
	@echo "$(BLUE)GraphQL API:$(NC) $(API_URL)"
	@echo "$(BLUE)PostgreSQL:$(NC)  localhost:$(DB_PORT)"
	@echo ""
	@echo "$(YELLOW)To run tests:$(NC) make test-only"
	@echo "$(YELLOW)To cleanup:$(NC)  make clean"
	@echo ""

.PHONY: ci
ci: check-deps-ci build up test clean ## CI/CD pipeline (with dependency checks)

# Build targets
.PHONY: build
build: ## Build the application with Maven
	@echo "$(BLUE)🔨 Building application...$(NC)"
	@mvn clean package -DskipTests -q
	@echo "$(GREEN)✓ Build completed$(NC)"

.PHONY: build-skip
build-skip: ## Skip Maven build (for rapid iteration)
	@echo "$(YELLOW)⚠️  Skipping Maven build$(NC)"

# Service management
.PHONY: up
up: check-ports ## Start Docker services
	@echo "$(BLUE)🚀 Starting services...$(NC)"
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) up -d --build > /dev/null 2>&1
	@echo "$(GREEN)✓ Services started$(NC)"
	@$(MAKE) --no-print-directory wait-ready

.PHONY: down
down: ## Stop Docker services
	@echo "$(BLUE)🛑 Stopping services...$(NC)"
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) down > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Services stopped$(NC)"

.PHONY: clean
clean: ## Stop services and cleanup volumes
	@echo "$(BLUE)🧹 Cleaning up...$(NC)"
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Cleanup completed$(NC)"

.PHONY: logs
logs: ## Show service logs
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) logs -f

.PHONY: logs-app
logs-app: ## Show application logs only
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) logs -f app

.PHONY: logs-db
logs-db: ## Show database logs only
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) logs -f postgres

.PHONY: status
status: ## Show service status
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) ps

# Testing targets
.PHONY: test
test: up test-only ## Start services and run tests
	@echo "$(GREEN)✓ Tests completed$(NC)"

.PHONY: test-only
test-only: ## Run e2e tests (requires services to be running)
	@echo "$(BLUE)🧪 Running E2E tests...$(NC)"
	@$(MAKE) --no-print-directory run-tests

.PHONY: test-quick
test-quick: build-skip up test-only ## Quick test (skip build)
	@echo "$(GREEN)✓ Quick tests completed$(NC)"

# Internal targets
.PHONY: check-deps
check-deps: ## Check required dependencies
	@echo "$(BLUE)🔍 Checking dependencies...$(NC)"
	@command -v docker >/dev/null 2>&1 || (echo "$(RED)❌ Docker not found$(NC)" && exit 1)
	@command -v docker-compose >/dev/null 2>&1 || (echo "$(RED)❌ Docker Compose not found$(NC)" && exit 1)
	@command -v mvn >/dev/null 2>&1 || (echo "$(RED)❌ Maven not found$(NC)" && exit 1)
	@command -v curl >/dev/null 2>&1 || (echo "$(RED)❌ curl not found$(NC)" && exit 1)
	@command -v jq >/dev/null 2>&1 || (echo "$(RED)❌ jq not found$(NC)" && exit 1)
	@echo "$(GREEN)✓ All dependencies available$(NC)"

.PHONY: check-deps-ci
check-deps-ci: ## Check dependencies for CI (no Maven required)
	@echo "$(BLUE)🔍 Checking CI dependencies...$(NC)"
	@command -v docker >/dev/null 2>&1 || (echo "$(RED)❌ Docker not found$(NC)" && exit 1)
	@(command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1) || (echo "$(RED)❌ Docker Compose not found$(NC)" && exit 1)
	@command -v curl >/dev/null 2>&1 || (echo "$(RED)❌ curl not found$(NC)" && exit 1)
	@command -v jq >/dev/null 2>&1 || (echo "$(RED)❌ jq not found$(NC)" && exit 1)
	@echo "$(GREEN)✓ All CI dependencies available$(NC)"

.PHONY: check-ports
check-ports: ## Check if required ports are available
	@echo "$(BLUE)🔍 Checking ports...$(NC)"
	@if lsof -i :$(APP_PORT) > /dev/null 2>&1; then \
		echo "$(RED)❌ Port $(APP_PORT) is already in use$(NC)"; \
		echo "$(YELLOW)💡 Stop the service using: lsof -ti:$(APP_PORT) | xargs kill$(NC)"; \
		exit 1; \
	fi
	@if lsof -i :$(DB_PORT) > /dev/null 2>&1; then \
		echo "$(RED)❌ Port $(DB_PORT) is already in use$(NC)"; \
		echo "$(YELLOW)💡 Stop the service using: lsof -ti:$(DB_PORT) | xargs kill$(NC)"; \
		exit 1; \
	fi
	@echo "$(GREEN)✓ Ports $(APP_PORT) and $(DB_PORT) are available$(NC)"

.PHONY: wait-ready
wait-ready: ## Wait for services to be ready
	@echo "$(BLUE)⏳ Waiting for services...$(NC)"
	@for i in $$(seq 1 30); do \
		if docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) exec -T postgres pg_isready -U excalibase_user -d excalibase_e2e > /dev/null 2>&1; then \
			echo "$(GREEN)✓ PostgreSQL ready$(NC)"; \
			break; \
		fi; \
		if [ $$i -eq 30 ]; then \
			echo "$(RED)❌ PostgreSQL failed to start$(NC)"; \
			exit 1; \
		fi; \
		printf "."; \
		sleep 2; \
	done
	@echo "$(BLUE)🔄 Waiting for application...$(NC)"
	@sleep 10
	@echo "$(GREEN)✓ All services ready$(NC)"

.PHONY: run-tests
run-tests: ## Execute the actual test suite
	@./scripts/e2e-test.sh || (echo "$(RED)❌ Tests failed$(NC)" && exit 1)

# Database operations (unified schema with demo + test data)
.PHONY: db-shell
db-shell: ## Connect to PostgreSQL shell
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) exec postgres psql -U excalibase_user -d excalibase_e2e

.PHONY: db-reset
db-reset: ## Reset database (recreate with fresh data)
	@echo "$(BLUE)🔄 Resetting database...$(NC)"
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) down -v > /dev/null 2>&1 || true
	@docker-compose -f $(COMPOSE_TEST_FILE) -p $(COMPOSE_PROJECT) up -d postgres > /dev/null 2>&1
	@$(MAKE) --no-print-directory wait-ready
	@echo "$(GREEN)✓ Database reset completed$(NC)"

# Utility targets
.PHONY: install-deps
install-deps: ## Install missing dependencies (macOS)
	@echo "$(BLUE)📦 Installing dependencies...$(NC)"
	@if ! command -v jq >/dev/null 2>&1; then \
		echo "$(YELLOW)Installing jq...$(NC)"; \
		brew install jq; \
	fi
	@if ! command -v docker >/dev/null 2>&1; then \
		echo "$(YELLOW)Please install Docker Desktop from: https://docker.com/products/docker-desktop$(NC)"; \
	fi
	@echo "$(GREEN)✓ Dependencies check completed$(NC)"

# Development workflow targets
.PHONY: restart
restart: down up ## Restart services
	@echo "$(GREEN)✓ Services restarted$(NC)"

.PHONY: rebuild
rebuild: clean build up ## Full rebuild and restart
	@echo "$(GREEN)✓ Full rebuild completed$(NC)"

# Aliases for convenience
.PHONY: start
start: e2e ## Alias for 'make e2e'

.PHONY: stop
stop: clean ## Alias for 'make clean'

.PHONY: ps
ps: status ## Alias for 'make status'

# Enterprise benchmark targets
.PHONY: benchmark
benchmark: check-deps benchmark-build benchmark-up benchmark-test benchmark-clean ## Complete enterprise benchmark test suite
	@echo "$(GREEN)🎉 Enterprise benchmark testing completed successfully!$(NC)"

.PHONY: benchmark-dev
benchmark-dev: check-deps benchmark-build benchmark-up ## Start enterprise benchmark services for development
	@echo ""
	@echo "$(GREEN)🚀 Enterprise benchmark environment ready!$(NC)"
	@echo ""
	@echo "$(BLUE)GraphQL API:$(NC) http://localhost:10002/graphql"
	@echo "$(BLUE)PostgreSQL:$(NC)  localhost:5434"
	@echo ""
	@echo "$(YELLOW)To run benchmark tests:$(NC) make benchmark-test-only"
	@echo "$(YELLOW)To cleanup:$(NC)         make benchmark-clean"
	@echo ""

.PHONY: benchmark-build
benchmark-build: ## Build application for enterprise benchmarking
	@echo "$(BLUE)🔨 Building application for enterprise benchmarking...$(NC)"
	@mvn clean package -DskipTests -q
	@echo "$(GREEN)✓ Enterprise benchmark build completed$(NC)"

.PHONY: benchmark-up
benchmark-up: benchmark-check-ports ## Start enterprise benchmark Docker services
	@echo "$(BLUE)🚀 Starting enterprise benchmark services...$(NC)"
	@docker-compose -f docker-compose.benchmark.yml -p excalibase-benchmark down -v --remove-orphans > /dev/null 2>&1 || true
	@docker-compose -f docker-compose.benchmark.yml -p excalibase-benchmark up -d --build
	@echo "$(GREEN)✓ Enterprise benchmark services started$(NC)"
	@$(MAKE) --no-print-directory benchmark-wait-ready

.PHONY: benchmark-down
benchmark-down: ## Stop enterprise benchmark Docker services
	@echo "$(BLUE)🛑 Stopping enterprise benchmark services...$(NC)"
	@docker-compose -f docker-compose.benchmark.yml -p excalibase-benchmark down > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Enterprise benchmark services stopped$(NC)"

.PHONY: benchmark-clean
benchmark-clean: ## Stop enterprise benchmark services and cleanup volumes
	@echo "$(BLUE)🧹 Cleaning up enterprise benchmark environment...$(NC)"
	@docker-compose -f docker-compose.benchmark.yml -p excalibase-benchmark down -v --remove-orphans > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Enterprise benchmark cleanup completed$(NC)"

.PHONY: benchmark-test
benchmark-test: benchmark-up benchmark-test-only ## Start services and run enterprise benchmark tests
	@echo "$(GREEN)✓ Enterprise benchmark tests completed$(NC)"

.PHONY: benchmark-test-only
benchmark-test-only: ## Run enterprise benchmark tests (requires services to be running)
	@echo "$(BLUE)🏢 Running Enterprise-Scale Benchmark Tests...$(NC)"
	@$(MAKE) --no-print-directory benchmark-run-tests

.PHONY: benchmark-check-ports
benchmark-check-ports: ## Check if enterprise benchmark ports are available
	@echo "$(BLUE)🔍 Checking enterprise benchmark ports...$(NC)"
	@if lsof -i :10002 > /dev/null 2>&1; then \
		echo "$(RED)❌ Port 10002 is already in use$(NC)"; \
		echo "$(YELLOW)💡 Stop the service using: lsof -ti:10002 | xargs kill$(NC)"; \
		exit 1; \
	fi
	@if lsof -i :5434 > /dev/null 2>&1; then \
		echo "$(RED)❌ Port 5434 is already in use$(NC)"; \
		echo "$(YELLOW)💡 Stop the service using: lsof -ti:5434 | xargs kill$(NC)"; \
		exit 1; \
	fi
	@echo "$(GREEN)✓ Ports 10002 and 5434 are available for enterprise benchmarking$(NC)"

.PHONY: benchmark-wait-ready
benchmark-wait-ready: ## Wait for enterprise benchmark services to be ready
	@echo "$(BLUE)⏳ Waiting for enterprise benchmark services...$(NC)"
	@echo "$(YELLOW)⚠️  Database initialization takes 2-5 minutes$(NC)"
	@echo ""
	@echo "$(BLUE)Waiting for PostgreSQL health check...$(NC)"
	@sleep 5
	@echo "$(BLUE)🔄 Waiting for database health check and application startup...$(NC)"
	@for i in $$(seq 1 150); do \
		if curl -s --connect-timeout 5 http://localhost:10002/graphql > /dev/null 2>&1; then \
			echo "$(GREEN)✓ Enterprise GraphQL API ready with full dataset!$(NC)"; \
			break; \
		fi; \
		if [ $$i -eq 150 ]; then \
			echo "$(RED)❌ Application failed to start with enterprise dataset$(NC)"; \
			echo "$(YELLOW)💡 Check logs: make benchmark-logs$(NC)"; \
			exit 1; \
		fi; \
		if [ $$((i % 12)) -eq 0 ]; then \
			printf "\n$(BLUE)Still waiting for services... ($$((i*5/60))min elapsed)$(NC)\n"; \
		else \
			printf "."; \
		fi; \
		sleep 5; \
	done
	@echo ""
	@echo "$(GREEN)✓ All enterprise benchmark services ready!$(NC)"

.PHONY: benchmark-run-tests
benchmark-run-tests: ## Execute the enterprise benchmark test suite
	@./scripts/e2e-benchmark.sh || (echo "$(RED)❌ Enterprise benchmark tests failed$(NC)" && exit 1)

.PHONY: benchmark-logs
benchmark-logs: ## Show enterprise benchmark service logs
	@docker-compose -f docker-compose.benchmark.yml -p excalibase-benchmark logs -f

.PHONY: benchmark-db-shell
benchmark-db-shell: ## Connect to enterprise benchmark PostgreSQL shell
	@docker-compose -f docker-compose.benchmark.yml -p excalibase-benchmark exec postgres psql -U excalibase_user -d excalibase_benchmark

.PHONY: benchmark-db-stats
benchmark-db-stats: ## Show enterprise benchmark database statistics
	@echo "$(BLUE)📊 Enterprise Benchmark Database Statistics$(NC)"
	@docker-compose -f docker-compose.benchmark.yml -p excalibase-benchmark exec postgres psql -U excalibase_user -d excalibase_benchmark -c "
		SELECT
			schemaname,
			tablename,
			n_tup_ins as inserts,
			n_tup_upd as updates,
			n_tup_del as deletes,
			n_live_tup as live_tuples,
			n_dead_tup as dead_tuples,
			pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as table_size
		FROM pg_stat_user_tables
		WHERE schemaname = 'hana'
		ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;"

# Force targets (ignore file existence)
.PHONY: docker-compose.yml $(COMPOSE_TEST_FILE) scripts/initdb.sql scripts/e2e-test.sh docker-compose.benchmark.yml scripts/benchmark-initdb.sql scripts/e2e-benchmark.sh