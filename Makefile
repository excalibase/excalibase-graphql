# Excalibase GraphQL Makefile
# Provides e2e testing and development commands

# Configuration
COMPOSE_PROJECT = excalibase-app
COMPOSE_FILE = docker-compose.yml
OBSERVABILITY_FILE = docker-compose.observability.yml
OBSERVABILITY_PROJECT = excalibase-obs
APP_PORT = 10000
DB_PORT = 5432
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
	@echo "  make e2e                  # Complete Postgres e2e test (JVM)"
	@echo "  make postgres-e2e-native  # Complete Postgres e2e test (native)"
	@echo "  make mysql-e2e            # Complete MySQL e2e test (JVM)"
	@echo "  make dev            # Build then start services and keep running"
	@echo "  make up             # Pull and start service from docker hub "
	@echo "  make test-only      # Run tests against running services"
	@echo "  make clean          # Stop services and cleanup"
	@echo ""
	@echo "$(YELLOW)Enterprise Benchmarking:$(NC)"
	@echo "  make benchmark              # Complete enterprise benchmark (16.7M+ records)"
	@echo "  make benchmark-dev          # Start enterprise benchmark environment"
	@echo "  make benchmark-test         # Run enterprise benchmark tests only"
	@echo ""
	@echo "$(YELLOW)K6 Load Testing:$(NC)"
	@echo "  make benchmark-k6-spring    # Run K6 benchmark against Spring Boot"
	@echo "  ./scripts/run-k6-benchmark.sh quick   # Quick 30s test"
	@echo "  ./scripts/run-k6-benchmark.sh full    # Full benchmark (~15min)"
	@echo ""
	@echo "$(YELLOW)Enterprise Debugging:$(NC)"
	@echo "  make benchmark-logs             # Show service logs"
	@echo "  make benchmark-db-shell         # Connect to database"
	@echo "  make benchmark-db-stats         # Show database statistics"
	@echo ""
	@echo "$(YELLOW)CI/CD Integration:$(NC)"
	@echo "  make ci              # Run complete CI pipeline"
	@echo "  make ci-benchmark    # Run enterprise benchmarks for CI"

# Main targets
.PHONY: e2e
e2e: down build-image up test clean ## Complete e2e test suite (cleanup, build image, test, cleanup)
	@echo "$(GREEN)🎉 E2E testing completed successfully!$(NC)"

.PHONY: dev
dev: build-image up ## Start services for development (no cleanup)
	@echo ""
	@echo "$(GREEN)🚀 Development environment ready!$(NC)"
	@echo ""
	@echo "$(BLUE)GraphQL API:$(NC) $(API_URL)"
	@echo "$(BLUE)PostgreSQL:$(NC)  localhost:$(DB_PORT)"
	@echo ""
	@echo "$(YELLOW)To run tests:$(NC) make test-only"
	@echo "$(YELLOW)To cleanup:$(NC)  make clean"
	@echo ""

.PHONY: test-all
test-all: ## Run all tests: unit + Postgres/MySQL/Multi-tenant E2E (JVM)
	@echo "$(BLUE)🧪 Running unit and integration tests...$(NC)"
	@mvn test -q || (echo "$(RED)❌ Unit/integration tests failed$(NC)" && exit 1)
	@echo "$(GREEN)✓ Unit/integration tests passed$(NC)"
	@$(MAKE) --no-print-directory e2e
	@$(MAKE) --no-print-directory mysql-e2e
	@$(MAKE) --no-print-directory multi-tenant-e2e
	@echo "$(GREEN)🎉 All tests passed!$(NC)"

.PHONY: ci
ci: build-image up test clean ## CI/CD pipeline

.PHONY: ci-benchmark
ci-benchmark: benchmark-build benchmark-up benchmark-test-only ## Enterprise benchmark for CI (without cleanup)
	@echo "$(GREEN)🏢 Enterprise CI benchmark completed!$(NC)"

# MySQL targets
MYSQL_COMPOSE_FILE = docker-compose.mysql.yml
MYSQL_COMPOSE_PROJECT = excalibase-mysql-app
MYSQL_API_PORT = 10001

# Native e2e targets
NATIVE_COMPOSE_FILE = docker-compose.native.yml
NATIVE_COMPOSE_PROJECT = excalibase-native-app

.PHONY: mysql-e2e
mysql-e2e: down-mysql build-image mysql-up mysql-test mysql-clean ## Complete MySQL e2e test suite (JVM)
	@echo "$(GREEN)🎉 MySQL E2E testing completed successfully!$(NC)"

.PHONY: postgres-e2e-native
postgres-e2e-native: down-native build-native-image up-native test-only-native clean-native ## Complete Postgres e2e test suite (native image)
	@echo "$(GREEN)🎉 Postgres Native E2E testing completed successfully!$(NC)"

.PHONY: mysql-dev
mysql-dev: build-image mysql-up ## Start MySQL services for development
	@echo ""
	@echo "$(GREEN)🚀 MySQL development environment ready!$(NC)"
	@echo "$(BLUE)GraphQL API:$(NC) http://localhost:$(MYSQL_API_PORT)/graphql"
	@echo "$(BLUE)MySQL:$(NC)       localhost:3306"
	@echo ""

.PHONY: mysql-up
mysql-up: ## Start MySQL Docker services
	@echo "$(BLUE)🚀 Starting MySQL services...$(NC)"
	@docker compose -f $(MYSQL_COMPOSE_FILE) -p $(MYSQL_COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@docker compose -f $(MYSQL_COMPOSE_FILE) -p $(MYSQL_COMPOSE_PROJECT) up -d 2>&1 || true
	@$(MAKE) --no-print-directory mysql-wait-ready

.PHONY: mysql-wait-ready
mysql-wait-ready: ## Wait for MySQL GraphQL API to be ready
	@echo "$(BLUE)⏳ Waiting for MySQL services...$(NC)"
	@for i in $$(seq 1 40); do \
		if curl -s -X POST http://localhost:$(MYSQL_API_PORT)/graphql -H 'Content-Type: application/json' -d '{"query":"{ __typename }"}' 2>/dev/null | grep -q "data\|error"; then \
			echo "$(GREEN)✓ MySQL GraphQL API ready$(NC)"; \
			break; \
		fi; \
		if [ $$i -eq 40 ]; then \
			echo "$(RED)❌ MySQL application failed to start$(NC)"; \
			exit 1; \
		fi; \
		printf "."; \
		sleep 3; \
	done

.PHONY: mysql-down
down-mysql: ## Stop MySQL Docker services
	@echo "$(BLUE)🛑 Stopping MySQL services...$(NC)"
	@docker compose -f $(MYSQL_COMPOSE_FILE) -p $(MYSQL_COMPOSE_PROJECT) down > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ MySQL services stopped$(NC)"

.PHONY: mysql-clean
mysql-clean: ## Stop MySQL services and cleanup volumes
	@docker compose -f $(MYSQL_COMPOSE_FILE) -p $(MYSQL_COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true

.PHONY: mysql-test
mysql-test: ## Run MySQL e2e tests (requires services running)
	@echo "$(BLUE)🧪 Running MySQL E2E tests...$(NC)"
	@cd e2e && npm install --silent && npm run test:mysql || (echo "$(RED)❌ MySQL tests failed$(NC)" && exit 1)
	@echo "$(BLUE)🧪 Running MySQL CDC subscription tests...$(NC)"
	@cd e2e && npm run test:subscription:mysql || (echo "$(RED)❌ MySQL subscription tests failed$(NC)" && exit 1)

# Multi-tenant targets
MT_COMPOSE_FILE = e2e/multi-tenant/docker-compose.multi-tenant.yml
MT_COMPOSE_PROJECT = excalibase-multi-tenant
MT_API_PORT = 10003

.PHONY: multi-tenant-e2e
multi-tenant-e2e: down-multi-tenant build-image multi-tenant-generate-keys multi-tenant-up multi-tenant-test multi-tenant-clean ## Complete multi-tenant e2e test suite
	@echo "$(GREEN)🎉 Multi-tenant E2E testing completed successfully!$(NC)"

.PHONY: multi-tenant-generate-keys
multi-tenant-generate-keys: ## Generate test EC keypair for multi-tenant WireMock
	@e2e/multi-tenant/generate-keys.sh

.PHONY: multi-tenant-up
multi-tenant-up: ## Start multi-tenant Docker services
	@echo "$(BLUE)🚀 Starting multi-tenant services...$(NC)"
	@docker compose -f $(MT_COMPOSE_FILE) -p $(MT_COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@docker compose -f $(MT_COMPOSE_FILE) -p $(MT_COMPOSE_PROJECT) up -d 2>&1 || true
	@echo "$(GREEN)✓ Multi-tenant services started$(NC)"
	@$(MAKE) --no-print-directory multi-tenant-wait-ready

.PHONY: down-multi-tenant
down-multi-tenant: ## Stop multi-tenant Docker services
	@echo "$(BLUE)🛑 Stopping multi-tenant services...$(NC)"
	@docker compose -f $(MT_COMPOSE_FILE) -p $(MT_COMPOSE_PROJECT) down > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Multi-tenant services stopped$(NC)"

.PHONY: multi-tenant-clean
multi-tenant-clean: ## Stop multi-tenant services and cleanup volumes
	@docker compose -f $(MT_COMPOSE_FILE) -p $(MT_COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true

.PHONY: multi-tenant-test
multi-tenant-test: ## Run multi-tenant e2e tests
	@echo "$(BLUE)🧪 Running multi-tenant E2E tests...$(NC)"
	@cd e2e && npm install --silent && npm run test:multi-tenant || (echo "$(RED)❌ Multi-tenant tests failed$(NC)" && exit 1)

.PHONY: multi-tenant-wait-ready
multi-tenant-wait-ready:
	@echo "$(BLUE)⏳ Waiting for multi-tenant services...$(NC)"
	@for i in $$(seq 1 30); do \
		if curl -s -X POST http://localhost:$(MT_API_PORT)/graphql -H 'Content-Type: application/json' -d '{"query":"{ __typename }"}' 2>/dev/null | grep -q "data\|error"; then \
			echo "$(GREEN)✓ Multi-tenant GraphQL API ready$(NC)"; \
			break; \
		fi; \
		if [ $$i -eq 30 ]; then \
			echo "$(RED)❌ Multi-tenant application failed to start$(NC)"; \
			exit 1; \
		fi; \
		printf "."; \
		sleep 3; \
	done

SC_COMPOSE_FILE = e2e/study-cases/docker-compose.study-cases.yml
SC_COMPOSE_PROJECT = excalibase-study-cases
SC_API_PORT = 10004
SC_AUTH_PORT = 24004

.PHONY: study-cases-e2e
study-cases-e2e: down-study-cases build-image study-cases-generate-keys study-cases-up study-cases-test study-cases-clean ## Complete study-cases e2e test suite

.PHONY: study-cases-generate-keys
study-cases-generate-keys: ## Generate test EC keypair for study-cases WireMock
	@e2e/study-cases/generate-keys.sh

.PHONY: study-cases-up
study-cases-up: ## Start study-cases Docker services
	@echo "$(BLUE)🚀 Starting study-cases services...$(NC)"
	@docker compose -f $(SC_COMPOSE_FILE) -p $(SC_COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@docker compose -f $(SC_COMPOSE_FILE) -p $(SC_COMPOSE_PROJECT) up -d 2>&1 || true
	@$(MAKE) --no-print-directory study-cases-wait-ready

.PHONY: down-study-cases
down-study-cases: ## Stop study-cases Docker services
	@echo "$(BLUE)🛑 Stopping study-cases services...$(NC)"
	@docker compose -f $(SC_COMPOSE_FILE) -p $(SC_COMPOSE_PROJECT) down > /dev/null 2>&1 || true

.PHONY: study-cases-clean
study-cases-clean: ## Stop study-cases services and cleanup volumes
	@docker compose -f $(SC_COMPOSE_FILE) -p $(SC_COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true

.PHONY: study-cases-test
study-cases-test: ## Run study-cases e2e tests
	@echo "$(BLUE)🧪 Running study-cases E2E tests...$(NC)"
	@cd e2e && SC_GRAPHQL_URL=http://localhost:$(SC_API_PORT)/graphql SC_AUTH_URL=http://localhost:$(SC_AUTH_PORT)/auth npm install --silent && \
	 SC_GRAPHQL_URL=http://localhost:$(SC_API_PORT)/graphql SC_AUTH_URL=http://localhost:$(SC_AUTH_PORT)/auth npm run test:study-cases || \
	 (echo "$(RED)❌ Study-cases tests failed$(NC)" && exit 1)

.PHONY: study-cases-wait-ready
study-cases-wait-ready:
	@echo "$(BLUE)⏳ Waiting for study-cases services...$(NC)"
	@for i in $$(seq 1 40); do \
		if curl -s -X POST http://localhost:$(SC_API_PORT)/graphql -H 'Content-Type: application/json' -d '{"query":"{ __typename }"}' 2>/dev/null | grep -q "data\|errors\|Unauthorized\|401"; then \
			echo "$(GREEN)✓ Study-cases GraphQL API ready$(NC)"; \
			break; \
		fi; \
		if [ $$i -eq 40 ]; then \
			echo "$(RED)❌ Study-cases application failed to start$(NC)"; \
			exit 1; \
		fi; \
		printf "."; \
		sleep 5; \
	done

# CDC subscription test targets
.PHONY: subscription-test
subscription-test: ## Run Postgres CDC subscription e2e tests (requires make up)
	@echo "$(BLUE)🧪 Running Postgres subscription tests...$(NC)"
	@cd e2e && npm install --silent && npm run test:subscription:postgres || (echo "$(RED)❌ Postgres subscription tests failed$(NC)" && exit 1)

.PHONY: mysql-subscription-test
mysql-subscription-test: ## Run MySQL CDC subscription e2e tests (requires mysql-up)
	@echo "$(BLUE)🧪 Running MySQL subscription tests...$(NC)"
	@cd e2e && npm install --silent && npm run test:subscription:mysql || (echo "$(RED)❌ MySQL subscription tests failed$(NC)" && exit 1)

# Benchmark targets (postgres and mysql, jvm and native)
.PHONY: benchmark-postgres-jvm
benchmark-postgres-jvm: ## Run Postgres JVM benchmark (quick)
	@./scripts/benchmark-postgres.sh jvm quick

.PHONY: benchmark-mysql-jvm
benchmark-mysql-jvm: ## Run MySQL JVM benchmark (quick)
	@./scripts/benchmark-mysql.sh jvm quick

# Build targets
.PHONY: build
build: ## Build the application with Maven
	@echo "$(BLUE)🔨 Building application...$(NC)"
	@mvn clean package -DskipTests -q
	@echo "$(GREEN)✓ Build completed$(NC)"

GRAALVM_HOME ?= $(HOME)/.sdkman/candidates/java/21.0.2-graalce

.PHONY: build-native
build-native: ## Build native image (requires GraalVM JDK 21, ~10 min)
	@echo "$(BLUE)🔨 Building native image...$(NC)"
	@JAVA_HOME=$(GRAALVM_HOME) PATH=$(GRAALVM_HOME)/bin:$(PATH) mvn -pl modules/excalibase-graphql-api -am -Pnative package -DskipTests -q
	@echo "$(GREEN)✓ Native image built: modules/excalibase-graphql-api/target/excalibase-graphql-api$(NC)"

.PHONY: build-native-image
build-native-image: build-native ## Build native Docker image
	@echo "$(BLUE)🐳 Building native Docker image...$(NC)"
	@docker build --no-cache -f Dockerfile.native -t excalibase/excalibase-graphql:native .
	@echo "$(GREEN)✓ Native Docker image built$(NC)"

.PHONY: build-image
build-image: build ## Build Docker image locally for e2e testing
	@echo "$(BLUE)🐳 Building Docker image...$(NC)"
	@docker build -t excalibase/excalibase-graphql .
	@echo "$(GREEN)✓ Docker image built$(NC)"

.PHONY: build-skip
build-skip: ## Skip Maven build (for rapid iteration)
	@echo "$(YELLOW)⚠️  Skipping Maven build$(NC)"

# Key generation for WireMock mock-vault
.PHONY: generate-keys
generate-keys: ## Generate test EC keypair and WireMock stubs
	@e2e/generate-keys.sh

# Service management
.PHONY: up
up: generate-keys ## Start Docker services (app + observability)
	@echo "$(BLUE)🚀 Starting services...$(NC)"
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) up -d 2>&1 || true
	@echo "$(GREEN)✓ App services started$(NC)"
	@echo "$(BLUE)📊 Starting observability stack...$(NC)"
	@docker compose -f $(OBSERVABILITY_FILE) -p $(OBSERVABILITY_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@docker compose -f $(OBSERVABILITY_FILE) -p $(OBSERVABILITY_PROJECT) up -d 2>&1 || true
	@echo "$(GREEN)✓ Observability services started$(NC)"
	@$(MAKE) --no-print-directory wait-ready

.PHONY: down
down: ## Stop Docker services (app + observability)
	@echo "$(BLUE)🛑 Stopping services...$(NC)"
	@docker compose -f $(OBSERVABILITY_FILE) -p $(OBSERVABILITY_PROJECT) down > /dev/null 2>&1 || true
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) down > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Services stopped$(NC)"

.PHONY: clean
clean: ## Stop services and cleanup volumes (app + observability)
	@echo "$(BLUE)🧹 Cleaning up...$(NC)"
	@docker compose -f $(OBSERVABILITY_FILE) -p $(OBSERVABILITY_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Cleanup completed$(NC)"

.PHONY: logs
logs: ## Show service logs
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) logs -f

.PHONY: logs-app
logs-app: ## Show application logs only
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) logs -f app

.PHONY: logs-db
logs-db: ## Show database logs only
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) logs -f postgres

.PHONY: status
status: ## Show service status
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) ps

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

.PHONY: wait-ready
wait-ready: ## Wait for services to be ready
	@echo "$(BLUE)⏳ Waiting for services...$(NC)"
	@for i in $$(seq 1 30); do \
		if docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) exec -T postgres pg_isready -U hana001 -d hana > /dev/null 2>&1; then \
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
	@for i in $$(seq 1 30); do \
		if curl -s -X POST http://localhost:$(APP_PORT)/graphql -H 'Content-Type: application/json' -d '{"query":"{ __typename }"}' 2>/dev/null | grep -q "data"; then \
			echo "$(GREEN)✓ GraphQL API ready$(NC)"; \
			break; \
		fi; \
		if [ $$i -eq 30 ]; then \
			echo "$(RED)❌ Application failed to start$(NC)"; \
			exit 1; \
		fi; \
		printf "."; \
		sleep 2; \
	done
	@echo "$(GREEN)✓ All services ready$(NC)"

.PHONY: up-native
up-native: generate-keys ## Start Postgres Docker services using native image
	@echo "$(BLUE)🚀 Starting native services...$(NC)"
	@docker compose -f $(NATIVE_COMPOSE_FILE) -p $(NATIVE_COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true
	@docker compose -f $(NATIVE_COMPOSE_FILE) -p $(NATIVE_COMPOSE_PROJECT) up -d 2>&1 || true
	@echo "$(GREEN)✓ Native services started$(NC)"
	@$(MAKE) --no-print-directory wait-ready-native

.PHONY: down-native
down-native: ## Stop native Postgres Docker services
	@echo "$(BLUE)🛑 Stopping native services...$(NC)"
	@docker compose -f $(NATIVE_COMPOSE_FILE) -p $(NATIVE_COMPOSE_PROJECT) down > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Native services stopped$(NC)"

.PHONY: clean-native
clean-native: ## Stop native Postgres services and cleanup volumes
	@docker compose -f $(NATIVE_COMPOSE_FILE) -p $(NATIVE_COMPOSE_PROJECT) down -v --remove-orphans > /dev/null 2>&1 || true

.PHONY: wait-ready-native
wait-ready-native: ## Wait for native services to be ready
	@echo "$(BLUE)⏳ Waiting for native services...$(NC)"
	@for i in $$(seq 1 30); do \
		if docker compose -f $(NATIVE_COMPOSE_FILE) -p $(NATIVE_COMPOSE_PROJECT) exec -T postgres pg_isready -U hana001 -d hana > /dev/null 2>&1; then \
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
	@echo "$(BLUE)🔄 Waiting for native application...$(NC)"
	@for i in $$(seq 1 30); do \
		if curl -s -X POST http://localhost:$(APP_PORT)/graphql -H 'Content-Type: application/json' -d '{"query":"{ __typename }"}' 2>/dev/null | grep -q "data"; then \
			echo "$(GREEN)✓ GraphQL API ready$(NC)"; \
			break; \
		fi; \
		if [ $$i -eq 30 ]; then \
			echo "$(RED)❌ Native application failed to start$(NC)"; \
			exit 1; \
		fi; \
		printf "."; \
		sleep 2; \
	done
	@echo "$(GREEN)✓ All native services ready$(NC)"

.PHONY: test-only-native
test-only-native: ## Run e2e tests against native containers
	@echo "$(BLUE)🧪 Running Native E2E tests...$(NC)"
	@$(MAKE) --no-print-directory run-tests-native

.PHONY: run-tests-native
run-tests-native: ## Execute test suite against native containers
	@cd e2e && npm install --silent && npm run test:postgres || (echo "$(RED)❌ Postgres tests failed$(NC)" && exit 1)
	@echo "$(BLUE)🧪 Running CDC subscription tests (native)...$(NC)"
	@cd e2e && PG_CONTAINER=excalibase-postgres-native npm run test:subscription:postgres || (echo "$(RED)❌ Subscription tests failed$(NC)" && exit 1)

.PHONY: run-tests
run-tests: ## Execute the actual test suite (queries/mutations + CDC subscriptions)
	@cd e2e && npm install --silent && npm run test:postgres || (echo "$(RED)❌ Postgres tests failed$(NC)" && exit 1)
	@echo "$(BLUE)🧪 Running CDC subscription tests...$(NC)"
	@cd e2e && npm run test:subscription:postgres || (echo "$(RED)❌ Subscription tests failed$(NC)" && exit 1)

# Database operations (unified schema with demo + test data)
.PHONY: db-shell
db-shell: ## Connect to PostgreSQL shell
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) exec postgres psql -U hana001 -d hana

.PHONY: db-reset
db-reset: ## Reset database (recreate with fresh data)
	@echo "$(BLUE)🔄 Resetting database...$(NC)"
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) down -v > /dev/null 2>&1 || true
	@docker compose -f $(COMPOSE_FILE) -p $(COMPOSE_PROJECT) up -d postgres > /dev/null 2>&1
	@$(MAKE) --no-print-directory wait-ready
	@echo "$(GREEN)✓ Database reset completed$(NC)"

# Development workflow targets
.PHONY: restart
restart: down up ## Restart services
	@echo "$(GREEN)✓ Services restarted$(NC)"

.PHONY: rebuild
rebuild: clean build up ## Full rebuild and restart
	@echo "$(GREEN)✓ Full rebuild completed$(NC)"

# Enterprise benchmark targets
.PHONY: benchmark
benchmark: benchmark-build benchmark-up benchmark-test benchmark-clean ## Complete enterprise benchmark test suite
	@echo "$(GREEN)🎉 Enterprise benchmark testing completed successfully!$(NC)"

.PHONY: benchmark-dev
benchmark-dev: benchmark-build benchmark-up ## Start enterprise benchmark services for development
	@echo ""
	@echo "$(GREEN)🚀 Enterprise benchmark environment ready!$(NC)"
	@echo ""
	@echo "$(BLUE)GraphQL API:$(NC) http://localhost:10002/graphql"
	@echo "$(BLUE)PostgreSQL:$(NC)  localhost:5434"
	@echo ""
	@echo "$(YELLOW)To run benchmark tests:$(NC) make benchmark-test-only"
	@echo "$(YELLOW)To cleanup:$(NC)         make benchmark-clean"
	@echo ""


.PHONY: benchmark-k6-spring
benchmark-k6-spring: ## Run K6 benchmark against Spring Boot
	@echo "$(BLUE)📊 Running K6 benchmark for Spring Boot...$(NC)"
	@./scripts/run-k6-benchmark.sh full

.PHONY: benchmark-build
benchmark-build: ## Build application for enterprise benchmarking
	@echo "$(BLUE)🔨 Building application for enterprise benchmarking...$(NC)"
	@mvn clean package -DskipTests -q
	@echo "$(GREEN)✓ Enterprise benchmark build completed$(NC)"

.PHONY: benchmark-up
benchmark-up: ## Start enterprise benchmark Docker services
	@echo "$(BLUE)🚀 Starting enterprise benchmark services...$(NC)"
	@docker compose -f scripts/docker-compose.benchmark.yml -p excalibase-benchmark down -v --remove-orphans > /dev/null 2>&1 || true
	@docker compose -f scripts/docker-compose.benchmark.yml -p excalibase-benchmark up -d --build
	@echo "$(GREEN)✓ Enterprise benchmark services started$(NC)"
	@$(MAKE) --no-print-directory benchmark-wait-ready

.PHONY: benchmark-down
benchmark-down: ## Stop enterprise benchmark Docker services
	@echo "$(BLUE)🛑 Stopping enterprise benchmark services...$(NC)"
	@docker compose -f scripts/docker-compose.benchmark.yml -p excalibase-benchmark down > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Enterprise benchmark services stopped$(NC)"

.PHONY: benchmark-clean
benchmark-clean: ## Stop enterprise benchmark services and cleanup volumes
	@echo "$(BLUE)🧹 Cleaning up enterprise benchmark environment...$(NC)"
	@docker compose -f scripts/docker-compose.benchmark.yml -p excalibase-benchmark down -v --remove-orphans > /dev/null 2>&1 || true
	@echo "$(GREEN)✓ Enterprise benchmark cleanup completed$(NC)"

.PHONY: benchmark-test
benchmark-test: benchmark-up benchmark-test-only ## Start services and run enterprise benchmark tests
	@echo "$(GREEN)✓ Enterprise benchmark tests completed$(NC)"

.PHONY: benchmark-test-only
benchmark-test-only: ## Run enterprise benchmark tests (requires services to be running)
	@echo "$(BLUE)🏢 Running Enterprise-Scale Benchmark Tests...$(NC)"
	@$(MAKE) --no-print-directory benchmark-run-tests


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
	@docker compose -f scripts/docker-compose.benchmark.yml -p excalibase-benchmark logs -f

.PHONY: benchmark-db-shell
benchmark-db-shell: ## Connect to enterprise benchmark PostgreSQL shell
	@docker compose -f scripts/docker-compose.benchmark.yml -p excalibase-benchmark exec postgres psql -U excalibase_user -d excalibase_benchmark

.PHONY: benchmark-db-stats
benchmark-db-stats: ## Show enterprise benchmark database statistics
	@echo "$(BLUE)📊 Enterprise Benchmark Database Statistics$(NC)"
	@docker compose -f scripts/docker-compose.benchmark.yml -p excalibase-benchmark exec postgres psql -U excalibase_user -d excalibase_benchmark -c "
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
.PHONY: docker-compose.yml scripts/initdb.sql scripts/docker-compose.benchmark.yml scripts/benchmark-initdb.sql scripts/e2e-benchmark.sh
