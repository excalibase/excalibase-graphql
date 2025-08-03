package io.github.excalibase.controller

import io.github.excalibase.benchmark.BenchmarkUtils
import io.github.excalibase.benchmark.EnterpriseBenchmarkReporter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Specification
import spock.lang.Shared

import java.sql.*
import java.util.concurrent.*

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class EnterpriseScaleBenchmarkTest extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("enterprise_test")
            .withUsername("testuser")
            .withPassword("testpass")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements",
                    "-c", "max_connections=200",
                    "-c", "work_mem=256MB",
                    "-c", "maintenance_work_mem=1GB")

    @Autowired
    WebApplicationContext webApplicationContext

    MockMvc mockMvc

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.datasource.hikari.schema", { "public" })
        registry.add("app.allowed-schema", { "public" })
        registry.add("app.database-type", { "postgres" })
        // Optimize for large dataset testing
        registry.add("spring.datasource.hikari.maximum-pool-size", { "50" })
        registry.add("spring.datasource.hikari.minimum-idle", { "10" })
    }

    def setupSpec() {
        postgres.start()
        setupEnterpriseScaleSchema()
    }

    def cleanupSpec() {
        // Generate comprehensive enterprise reports
        println("📊 Generating comprehensive enterprise benchmark reports...")

        try {
            EnterpriseBenchmarkReporter.generateEnterpriseBenchmarkReportWithSystemMonitoring(
                    BenchmarkUtils.getAllResults(),
                    "target/enterprise-benchmark-reports"
            )
            println("✅ Enterprise benchmark reports with system monitoring generated successfully!")
            println("📋 Open target/enterprise-benchmark-reports/enterprise-benchmark-report.html for detailed analysis")
            println("🖥️  System monitoring: target/enterprise-benchmark-reports/system-monitoring.html")
        } catch (Exception e) {
            println("⚠️ Error generating enterprise reports: ${e.getMessage()}")
        }

        postgres.stop()
    }

    def setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    private static void setupEnterpriseScaleSchema() {
        println("🏗️ Setting up Enterprise-Scale Test Schema...")

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement statement = connection.createStatement()) {

            // Create comprehensive enterprise-like schema
            statement.execute("""
                CREATE SCHEMA IF NOT EXISTS public;
                SET search_path TO public;
                
                -- Enable extensions for better performance monitoring
                CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
            """)

            createEnterpriseSchema(statement)
            populateEnterpriseData(statement)
            createIndexes(statement)
            updateStatistics(statement)

            println("✅ Enterprise-Scale Schema Setup Complete!")

        } catch (Exception e) {
            println("❌ Error setting up enterprise schema: ${e.getMessage()}")
            e.printStackTrace()
        }
    }

    private static void createEnterpriseSchema(Statement statement) {
        println("📋 Creating 50+ table enterprise schema...")

        // Core business entities
        statement.execute("""
            CREATE TABLE companies (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                industry VARCHAR(100),
                founded_year INTEGER,
                revenue NUMERIC(15,2),
                employee_count INTEGER,
                headquarters JSONB,
                locations JSONB[],
                created_at TIMESTAMPTZ DEFAULT NOW(),
                updated_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        statement.execute("""
            CREATE TABLE departments (
                id SERIAL PRIMARY KEY,
                company_id INTEGER REFERENCES companies(id),
                name VARCHAR(100) NOT NULL,
                budget NUMERIC(12,2),
                manager_id INTEGER,
                cost_center VARCHAR(20),
                department_metadata JSONB,
                created_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        statement.execute("""
            CREATE TABLE employees (
                id SERIAL PRIMARY KEY,
                company_id INTEGER REFERENCES companies(id),
                department_id INTEGER REFERENCES departments(id),
                employee_number VARCHAR(20) UNIQUE,
                first_name VARCHAR(100) NOT NULL,
                last_name VARCHAR(100) NOT NULL,
                email VARCHAR(255) UNIQUE,
                phone VARCHAR(20),
                hire_date DATE,
                salary NUMERIC(10,2),
                bonus_eligible BOOLEAN DEFAULT false,
                employee_level INTEGER DEFAULT 1,
                skills TEXT[],
                certifications JSONB,
                address JSONB,
                emergency_contacts JSONB[],
                performance_ratings NUMERIC(3,2)[],
                created_at TIMESTAMPTZ DEFAULT NOW(),
                updated_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        statement.execute("""
            CREATE TABLE projects (
                id SERIAL PRIMARY KEY,
                company_id INTEGER REFERENCES companies(id),
                name VARCHAR(200) NOT NULL,
                description TEXT,
                project_type VARCHAR(50),
                status VARCHAR(20) DEFAULT 'ACTIVE',
                budget NUMERIC(12,2),
                start_date DATE,
                end_date DATE,
                priority INTEGER DEFAULT 3,
                project_manager_id INTEGER REFERENCES employees(id),
                stakeholders INTEGER[],
                requirements JSONB,
                milestones JSONB[],
                risk_assessment JSONB,
                created_at TIMESTAMPTZ DEFAULT NOW(),
                updated_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        statement.execute("""
            CREATE TABLE project_assignments (
                id SERIAL PRIMARY KEY,
                project_id INTEGER REFERENCES projects(id),
                employee_id INTEGER REFERENCES employees(id),
                role VARCHAR(100),
                allocation_percentage NUMERIC(5,2),
                hourly_rate NUMERIC(8,2),
                start_date DATE,
                end_date DATE,
                is_billable BOOLEAN DEFAULT true,
                created_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        statement.execute("""
            CREATE TABLE time_entries (
                id SERIAL PRIMARY KEY,
                employee_id INTEGER REFERENCES employees(id),
                project_id INTEGER REFERENCES projects(id),
                entry_date DATE NOT NULL,
                hours_worked NUMERIC(4,2),
                overtime_hours NUMERIC(4,2) DEFAULT 0,
                activity_type VARCHAR(50),
                description TEXT,
                billable BOOLEAN DEFAULT true,
                billing_rate NUMERIC(8,2),
                approved_by INTEGER REFERENCES employees(id),
                approval_date TIMESTAMPTZ,
                metadata JSONB,
                created_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        // Financial tables
        statement.execute("""
            CREATE TABLE invoices (
                id SERIAL PRIMARY KEY,
                company_id INTEGER REFERENCES companies(id),
                project_id INTEGER REFERENCES projects(id),
                invoice_number VARCHAR(50) UNIQUE,
                amount NUMERIC(12,2),
                tax_amount NUMERIC(10,2),
                total_amount NUMERIC(12,2),
                currency VARCHAR(3) DEFAULT 'USD',
                issue_date DATE,
                due_date DATE,
                paid_date DATE,
                status VARCHAR(20) DEFAULT 'PENDING',
                line_items JSONB[],
                payment_terms JSONB,
                created_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        statement.execute("""
            CREATE TABLE expenses (
                id SERIAL PRIMARY KEY,
                employee_id INTEGER REFERENCES employees(id),
                project_id INTEGER REFERENCES projects(id),
                expense_date DATE,
                amount NUMERIC(10,2),
                currency VARCHAR(3) DEFAULT 'USD',
                category VARCHAR(50),
                description TEXT,
                receipt_url VARCHAR(500),
                reimbursable BOOLEAN DEFAULT true,
                approved BOOLEAN DEFAULT false,
                expense_details JSONB,
                created_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        // Analytics and logging tables
        statement.execute("""
            CREATE TABLE audit_logs (
                id SERIAL PRIMARY KEY,
                table_name VARCHAR(100),
                record_id INTEGER,
                action VARCHAR(20),
                old_values JSONB,
                new_values JSONB,
                changed_by INTEGER REFERENCES employees(id),
                ip_address INET,
                user_agent TEXT,
                session_data JSONB,
                created_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        statement.execute("""
            CREATE TABLE performance_metrics (
                id SERIAL PRIMARY KEY,
                entity_type VARCHAR(50),
                entity_id INTEGER,
                metric_name VARCHAR(100),
                metric_value NUMERIC(15,4),
                measurement_date DATE,
                metadata JSONB,
                tags TEXT[],
                created_at TIMESTAMPTZ DEFAULT NOW()
            );
        """)

        // Add more tables to reach 50+ (continuing pattern)
        for (int i = 1; i <= 40; i++) {
            statement.execute("""
                CREATE TABLE data_table_${i} (
                    id SERIAL PRIMARY KEY,
                    company_id INTEGER REFERENCES companies(id),
                    reference_id INTEGER,
                    name VARCHAR(200),
                    category VARCHAR(50),
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    priority INTEGER DEFAULT 3,
                    data_payload JSONB,
                    tags TEXT[],
                    numeric_values NUMERIC(10,2)[],
                    timestamps TIMESTAMPTZ[],
                    config_data JSONB,
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    updated_at TIMESTAMPTZ DEFAULT NOW()
                );
            """)
        }
    }

    private static void populateEnterpriseData(Statement statement) {
        println("📊 Populating with enterprise-scale data using batch operations...")

        // Enable batch optimization
        statement.execute("SET synchronous_commit = off;")
        statement.execute("SET wal_buffers = '64MB';")
        statement.execute("SET checkpoint_segments = 32;")

        try {
            // Use batch inserts for much better performance
            populateCompaniesBatch(statement)
            populateDepartmentsBatch(statement)
            populateEmployeesBatch(statement)
            populateProjectsBatch(statement)
            populateTimeEntriesBatch(statement)
            populateAuditLogsBatch(statement)

        } finally {
            // Re-enable normal commit behavior
            statement.execute("SET synchronous_commit = on;")
        }
    }

    private static void populateCompaniesBatch(Statement statement) {
        println("   📊 Batch inserting 10,000 companies...")

        def batchSize = 1000
        def totalCompanies = 10000

        for (int batch = 0; batch < totalCompanies; batch += batchSize) {
            def values = new StringBuilder()

            for (int i = batch; i < Math.min(batch + batchSize, totalCompanies); i++) {
                if (values.length() > 0) values.append(",")
                values.append("""
                    ('Company ${i}',
                     '${getRandomIndustry(i)}',
                     ${1950 + (i % 70)},
                     ${(i * 1000000) + (i % 50000)},
                     ${100 + (i % 5000)},
                     '{"city": "City${i % 100}", "country": "Country${i % 50}", "address": "Address ${i}"}',
                     ARRAY['{"city": "Branch${i % 200}", "country": "Country${(i+1) % 50}"}']::JSONB[])
                """)
            }

            statement.execute("""
                INSERT INTO companies (name, industry, founded_year, revenue, employee_count, headquarters, locations) 
                VALUES ${values.toString()};
            """)

            if ((batch + batchSize) % 5000 == 0) {
                println("      Inserted ${batch + batchSize} companies...")
            }
        }
    }

    private static void populateDepartmentsBatch(Statement statement) {
        println("   📊 Batch inserting 100,000 departments...")

        def batchSize = 2000
        def totalDepartments = 100000

        for (int batch = 0; batch < totalDepartments; batch += batchSize) {
            def values = new StringBuilder()

            for (int i = batch; i < Math.min(batch + batchSize, totalDepartments); i++) {
                if (values.length() > 0) values.append(",")
                int companyId = 1 + (i % 10000)
                values.append("""
                    (${companyId},
                     '${getRandomDepartment(i)} ${i}',
                     ${500000 + (i % 1000000)},
                     'CC-${i}',
                     '{"type": "operational", "region": "Region${i % 20}", "head_count": ${10 + (i % 50)}}')
                """)
            }

            statement.execute("""
                INSERT INTO departments (company_id, name, budget, cost_center, department_metadata) 
                VALUES ${values.toString()};
            """)

            if ((batch + batchSize) % 20000 == 0) {
                println("      Inserted ${batch + batchSize} departments...")
            }
        }
    }

    private static void populateEmployeesBatch(Statement statement) {
        println("   📊 Batch inserting 1,000,000 employees...")

        def batchSize = 5000
        def totalEmployees = 1000000

        for (int batch = 0; batch < totalEmployees; batch += batchSize) {
            def values = new StringBuilder()

            for (int i = batch; i < Math.min(batch + batchSize, totalEmployees); i++) {
                if (values.length() > 0) values.append(",")
                int companyId = 1 + (i % 10000)
                int departmentId = 1 + (i % 100000)
                values.append("""
                    (${companyId}, ${departmentId}, 'EMP${String.format("%06d", i)}',
                     'First${i}', 'Last${i}', 'employee${i}@company${companyId}.com',
                     ${30000 + (i % 150000)}, ${1 + (i % 10)},
                     ARRAY['${getRandomSkills(i)}'],
                     '{"certs": ["Cert${i % 20}", "Cert${(i+1) % 20}"], "training_hours": ${40 + (i % 200)}}',
                     '{"street": "Street ${i}", "city": "City${i % 100}", "zip": "${10000 + (i % 90000)}"}')
                """)
            }

            statement.execute("""
                INSERT INTO employees (
                    company_id, department_id, employee_number, first_name, last_name, 
                    email, salary, employee_level, skills, certifications, address
                ) VALUES ${values.toString()};
            """)

            if ((batch + batchSize) % 100000 == 0) {
                println("      Inserted ${batch + batchSize} employees...")
            }
        }
    }

    private static void populateProjectsBatch(Statement statement) {
        println("   📊 Batch inserting 50,000 projects...")

        def batchSize = 2000
        def totalProjects = 50000

        for (int batch = 0; batch < totalProjects; batch += batchSize) {
            def values = new StringBuilder()

            for (int i = batch; i < Math.min(batch + batchSize, totalProjects); i++) {
                if (values.length() > 0) values.append(",")
                int companyId = 1 + (i % 10000)
                values.append("""
                    (${companyId}, 'Project ${i}', ${100000 + (i % 5000000)}, ${1 + (i % 5)},
                     '{"scope": "Scope${i}", "technologies": ["Tech${i % 10}", "Tech${(i+1) % 10}"]}')
                """)
            }

            statement.execute("""
                INSERT INTO projects (company_id, name, budget, priority, requirements) 
                VALUES ${values.toString()};
            """)
        }
    }

    private static void populateTimeEntriesBatch(Statement statement) {
        println("   📊 Batch inserting 5,000,000 time entries (this is the big one)...")

        def batchSize = 10000  // Larger batches for the huge table
        def totalTimeEntries = 5000000

        for (int batch = 0; batch < totalTimeEntries; batch += batchSize) {
            def values = new StringBuilder()

            for (int i = batch; i < Math.min(batch + batchSize, totalTimeEntries); i++) {
                if (values.length() > 0) values.append(",")
                int employeeId = 1 + (i % 1000000)
                int projectId = 1 + (i % 50000)
                values.append("""
                    (${employeeId}, ${projectId}, 
                     '2023-01-01'::date + interval '${i % 365} days',
                     ${1 + (i % 12)}, '${getRandomActivity(i)}', ${50 + (i % 200)},
                     '{"project_phase": "Phase${i % 5}", "complexity": ${1 + (i % 5)}}')
                """)
            }

            statement.execute("""
                INSERT INTO time_entries (
                    employee_id, project_id, entry_date, hours_worked, 
                    activity_type, billing_rate, metadata
                ) VALUES ${values.toString()};
            """)

            if ((batch + batchSize) % 500000 == 0) {
                println("      Inserted ${batch + batchSize} time entries...")
            }
        }
    }

    private static void populateAuditLogsBatch(Statement statement) {
        println("   📊 Batch inserting 10,000,000 audit logs (the stress test table)...")

        def batchSize = 20000  // Very large batches for maximum performance
        def totalAuditLogs = 10000000

        for (int batch = 0; batch < totalAuditLogs; batch += batchSize) {
            def values = new StringBuilder()

            for (int i = batch; i < Math.min(batch + batchSize, totalAuditLogs); i++) {
                if (values.length() > 0) values.append(",")
                values.append("""
                    ('table_${i % 20}', ${i % 1000000}, '${getRandomAction(i)}',
                     '{"old": "value${i}"}', '{"new": "value${i+1}"}',
                     '192.168.${(i % 254) + 1}.${(i % 254) + 1}',
                     '{"session_id": "sess_${i}", "duration": ${i % 3600}}')
                """)
            }

            statement.execute("""
                INSERT INTO audit_logs (table_name, record_id, action, old_values, new_values, ip_address, session_data) 
                VALUES ${values.toString()};
            """)

            if ((batch + batchSize) % 1000000 == 0) {
                println("      Inserted ${batch + batchSize} audit logs...")
            }
        }
    }

    private static void populateRemainingTables(Statement statement) {
        // This method is now replaced by individual batch methods above
        // Keep any additional tables that need population here
        println("   📊 Populating remaining tables...")

        // Add any other tables using the same batch pattern
        populateInvoicesBatch(statement)
        populateExpensesBatch(statement)
        populateDataTablesBatch(statement)
    }

    private static void populateInvoicesBatch(Statement statement) {
        println("   📊 Batch inserting 100,000 invoices...")

        def batchSize = 2000
        def totalInvoices = 100000

        for (int batch = 0; batch < totalInvoices; batch += batchSize) {
            def values = new StringBuilder()

            for (int i = batch; i < Math.min(batch + batchSize, totalInvoices); i++) {
                if (values.length() > 0) values.append(",")
                int companyId = 1 + (i % 10000)
                int projectId = 1 + (i % 50000)
                values.append("""
                    (${companyId}, ${projectId}, 'INV-${String.format("%06d", i)}',
                     ${10000 + (i % 500000)}, ${(i % 5000)}, ${10000 + (i % 500000) + (i % 5000)},
                     'USD', '2023-01-01'::date + interval '${i % 365} days',
                     '2023-01-01'::date + interval '${(i % 365) + 30} days',
                     'PENDING', ARRAY['{"item": "Service${i % 10}", "amount": ${1000 + (i % 10000)}}']::JSONB[],
                     '{"terms": "Net 30", "payment_method": "ACH"}')
                """)
            }

            statement.execute("""
                INSERT INTO invoices (
                    company_id, project_id, invoice_number, amount, tax_amount, 
                    total_amount, currency, issue_date, due_date, status, line_items, payment_terms
                ) VALUES ${values.toString()};
            """)
        }
    }

    private static void populateExpensesBatch(Statement statement) {
        println("   📊 Batch inserting 500,000 expenses...")

        def batchSize = 5000
        def totalExpenses = 500000

        for (int batch = 0; batch < totalExpenses; batch += batchSize) {
            def values = new StringBuilder()

            for (int i = batch; i < Math.min(batch + batchSize, totalExpenses); i++) {
                if (values.length() > 0) values.append(",")
                int employeeId = 1 + (i % 1000000)
                int projectId = 1 + (i % 50000)
                values.append("""
                    (${employeeId}, ${projectId}, '2023-01-01'::date + interval '${i % 365} days',
                     ${10 + (i % 5000)}, 'USD', '${getRandomExpenseCategory(i)}',
                     'Expense description ${i}', 'https://receipts.com/${i}',
                     ${i % 2 == 0}, ${i % 3 == 0},
                     '{"vendor": "Vendor${i % 100}", "receipt_number": "REC-${i}"}')
                """)
            }

            statement.execute("""
                INSERT INTO expenses (
                    employee_id, project_id, expense_date, amount, currency, 
                    category, description, receipt_url, reimbursable, approved, expense_details
                ) VALUES ${values.toString()};
            """)
        }
    }

    private static void populateDataTablesBatch(Statement statement) {
        println("   📊 Batch inserting data into 40 additional tables...")

        def batchSize = 1000
        def recordsPerTable = 10000

        for (int tableNum = 1; tableNum <= 40; tableNum++) {
            for (int batch = 0; batch < recordsPerTable; batch += batchSize) {
                def values = new StringBuilder()

                for (int i = batch; i < Math.min(batch + batchSize, recordsPerTable); i++) {
                    if (values.length() > 0) values.append(",")
                    int companyId = 1 + (i % 10000)
                    int recordIndex = (tableNum * recordsPerTable) + i
                    values.append("""
                        (${companyId}, ${recordIndex}, 'Record ${recordIndex}',
                         'Category${recordIndex % 20}', 'ACTIVE', ${1 + (recordIndex % 5)},
                         '{"table": ${tableNum}, "record": ${i}, "metadata": "data_${recordIndex}"}',
                         ARRAY['tag${recordIndex % 10}', 'tag${(recordIndex+1) % 10}'],
                         ARRAY[${recordIndex % 1000}, ${(recordIndex+1) % 1000}],
                         ARRAY['2023-01-01 10:00:00+00'::timestamptz + interval '${recordIndex % 365} days'],
                         '{"config": "value${recordIndex}", "settings": {"enabled": ${recordIndex % 2 == 0}}}')
                    """)
                }

                statement.execute("""
                    INSERT INTO data_table_${tableNum} (
                        company_id, reference_id, name, category, status, priority,
                        data_payload, tags, numeric_values, timestamps, config_data
                    ) VALUES ${values.toString()};
                """)
            }
        }
    }

    private static String getRandomExpenseCategory(int seed) {
        def categories = ["Travel", "Meals", "Office Supplies", "Software", "Training",
                          "Equipment", "Marketing", "Legal", "Consulting", "Utilities"]
        return categories[seed % categories.size()]
    }

    private static void createIndexes(Statement statement) {
        println("🔍 Creating enterprise-grade indexes...")

        // Companies indexes
        statement.execute("CREATE INDEX idx_companies_industry ON companies(industry);")
        statement.execute("CREATE INDEX idx_companies_revenue ON companies(revenue);")
        statement.execute("CREATE INDEX idx_companies_employee_count ON companies(employee_count);")

        // Employees indexes
        statement.execute("CREATE INDEX idx_employees_company_dept ON employees(company_id, department_id);")
        statement.execute("CREATE INDEX idx_employees_email ON employees(email);")
        statement.execute("CREATE INDEX idx_employees_salary ON employees(salary);")
        statement.execute("CREATE INDEX idx_employees_hire_date ON employees(hire_date);")
        statement.execute("CREATE INDEX idx_employees_level ON employees(employee_level);")

        // Time entries indexes (critical for performance)
        statement.execute("CREATE INDEX idx_time_entries_employee ON time_entries(employee_id);")
        statement.execute("CREATE INDEX idx_time_entries_project ON time_entries(project_id);")
        statement.execute("CREATE INDEX idx_time_entries_date ON time_entries(entry_date);")
        statement.execute("CREATE INDEX idx_time_entries_emp_date ON time_entries(employee_id, entry_date);")

        // Audit logs indexes
        statement.execute("CREATE INDEX idx_audit_logs_table_record ON audit_logs(table_name, record_id);")
        statement.execute("CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);")
        statement.execute("CREATE INDEX idx_audit_logs_action ON audit_logs(action);")

        // Projects indexes
        statement.execute("CREATE INDEX idx_projects_company ON projects(company_id);")
        statement.execute("CREATE INDEX idx_projects_status ON projects(status);")
        statement.execute("CREATE INDEX idx_projects_dates ON projects(start_date, end_date);")

        // JSONB indexes for enhanced types
        statement.execute("CREATE INDEX idx_companies_headquarters_gin ON companies USING GIN (headquarters);")
        statement.execute("CREATE INDEX idx_employees_skills_gin ON employees USING GIN (skills);")
        statement.execute("CREATE INDEX idx_time_entries_metadata_gin ON time_entries USING GIN (metadata);")
    }

    private static void updateStatistics(Statement statement) {
        println("📈 Updating table statistics...")
        statement.execute("ANALYZE;")
    }

    // Helper methods for data generation
    private static String getRandomIndustry(int seed) {
        def industries = ["Technology", "Healthcare", "Finance", "Manufacturing", "Retail",
                          "Education", "Energy", "Transportation", "Real Estate", "Media"]
        return industries[seed % industries.size()]
    }

    private static String getRandomDepartment(int seed) {
        def departments = ["Engineering", "Sales", "Marketing", "HR", "Finance",
                           "Operations", "Legal", "IT", "Support", "Research"]
        return departments[seed % departments.size()]
    }

    private static String getRandomSkills(int seed) {
        def skills = ["Java", "Python", "JavaScript", "SQL", "Project Management",
                      "Data Analysis", "Machine Learning", "DevOps", "Marketing", "Design"]
        return skills[seed % skills.size()]
    }

    private static String getRandomActivity(int seed) {
        def activities = ["Development", "Testing", "Analysis", "Design", "Meetings",
                          "Documentation", "Research", "Planning", "Review", "Support"]
        return activities[seed % activities.size()]
    }

    private static String getRandomAction(int seed) {
        def actions = ["INSERT", "UPDATE", "DELETE", "SELECT"]
        return actions[seed % actions.size()]
    }

    // ============================================================================
    // ENTERPRISE-SCALE PERFORMANCE TESTS
    // ============================================================================

    def "should handle massive table count schema introspection"() {
        given: "50+ table enterprise schema"

        when: "introspecting large schema via GraphQL schema endpoint"
        def measurement = BenchmarkUtils.startMeasurement(
                "EnterpriseScaleBenchmark",
                "schema_introspection_50_tables"
        )

        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content('{"query": "{ __schema { types { name fields { name type { name } } } } }"}'))

        def benchmarkResult = measurement
                .metadata("table_count", 50)
                .metadata("schema_complexity", "enterprise")
                .complete()

        then: "should complete schema introspection within reasonable time"
        result.andExpect(status().isOk())
        benchmarkResult.getDurationMs() < 5000  // 5 second max for schema introspection

        println("✅ Schema introspection (50+ tables): ${benchmarkResult.getDurationMs()}ms")
    }

    def "should handle million-record table queries efficiently"() {
        given: "query against 1M employee records"
        def query = '''
        {
            employees(where: { 
                salary: { gte: 50000, lte: 100000 },
                employee_level: { gte: 3 }
            }) {
                id
                first_name
                last_name
                salary
                employee_level
                department { name }
                company { name }
            }
        }
        '''

        when: "executing complex query on large dataset"
        def measurement = BenchmarkUtils.startMeasurement(
                "EnterpriseScaleBenchmark",
                "million_record_query"
        )

        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        def benchmarkResult = measurement
                .recordCount(1000000)
                .metadata("query_type", "complex_filtering_with_joins")
                .metadata("tables_involved", ["employees", "departments", "companies"])
                .complete()

        then: "should complete within enterprise SLA"
        result.andExpect(status().isOk())
        benchmarkResult.getDurationMs() < 2000  // 2 second max for complex queries

        println("✅ Million-record query: ${benchmarkResult.getDurationMs()}ms")
    }

    def "should handle massive JOIN operations performance"() {
        given: "complex multi-table JOIN query"
        def query = '''
        {
            time_entries(where: { 
                hours_worked: { gte: 8 },
                entry_date: { gte: "2023-06-01", lte: "2023-12-31" }
            }) {
                id
                hours_worked
                entry_date
                employee {
                    first_name
                    last_name
                    department {
                        name
                        company {
                            name
                            industry
                        }
                    }
                }
                project {
                    name
                    budget
                }
            }
        }
        '''

        when: "executing 5M record JOIN query"
        def measurement = BenchmarkUtils.startMeasurement(
                "EnterpriseScaleBenchmark",
                "massive_join_5m_records"
        )

        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        def benchmarkResult = measurement
                .recordCount(5000000)
                .metadata("query_type", "complex_multi_table_joins")
                .metadata("tables_involved", ["time_entries", "employees", "departments", "companies", "projects"])
                .metadata("join_depth", 4)
                .complete()

        then: "should handle massive JOINs efficiently"
        result.andExpect(status().isOk())
        benchmarkResult.getDurationMs() < 3000  // 3 second max for massive JOINs

        println("✅ Massive JOIN query (5M records): ${benchmarkResult.getDurationMs()}ms")
    }

    def "should handle enhanced types at enterprise scale"() {
        given: "complex JSONB and array queries"
        def query = '''
        {
            employees(where: {
                certifications: { contains: "training_hours" },
                skills: { contains: "Java" }
            }) {
                id
                first_name
                skills
                certifications
                address
            }
        }
        '''

        when: "querying enhanced types on large dataset"
        def measurement = BenchmarkUtils.startMeasurement(
                "EnterpriseScaleBenchmark",
                "enhanced_types_enterprise_scale"
        )

        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        def benchmarkResult = measurement
                .recordCount(1000000)
                .metadata("query_type", "enhanced_types_filtering")
                .metadata("enhanced_features", ["jsonb_contains", "array_contains"])
                .complete()

        then: "should handle enhanced types efficiently at scale"
        result.andExpect(status().isOk())
        benchmarkResult.getDurationMs() < 1500  // 1.5 second max for enhanced types

        println("✅ Enhanced types at scale: ${benchmarkResult.getDurationMs()}ms")
    }

    def "should handle extreme concurrent load"() {
        given: "50 concurrent complex queries"
        def executor = Executors.newFixedThreadPool(50)
        def futures = []

        def query = '''
        {
            employees(where: { salary: { gte: 75000 } }) {
                id first_name last_name salary
                department { name }
                company { name }
            }
        }
        '''

        when: "executing 50 concurrent requests"
        def measurement = BenchmarkUtils.startMeasurement(
                "EnterpriseScaleBenchmark",
                "extreme_concurrent_load_50_threads"
        )

        50.times { i ->
            futures << executor.submit({
                return mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                        .andExpect(status().isOk())
                        .andReturn()
            })
        }

        // Wait for all requests to complete
        futures.each { it.get() }
        executor.shutdown()

        def benchmarkResult = measurement
                .recordCount(1000000)
                .metadata("concurrent_threads", 50)
                .metadata("query_type", "concurrent_employee_queries")
                .metadata("total_requests", futures.size())
                .complete()

        then: "should handle extreme concurrency"
        benchmarkResult.getDurationMs() < 10000  // 10 second max for 50 concurrent requests

        println("✅ 50 concurrent requests: ${benchmarkResult.getDurationMs()}ms")
    }

    def "should handle memory pressure with large result sets"() {
        given: "query returning large result set"
        def query = '''
        {
            audit_logs(where: { 
                created_at: { gte: "2023-01-01" },
                action: { in: ["INSERT", "UPDATE"] }
            }) {
                id
                table_name
                action
                old_values
                new_values
                created_at
            }
        }
        '''

        when: "executing memory-intensive query"
        def runtime = Runtime.getRuntime()

        // Force GC before measurement for accurate baseline
        System.gc()
        Thread.sleep(100)
        def memoryBefore = runtime.totalMemory() - runtime.freeMemory()

        def measurement = BenchmarkUtils.startMeasurement(
                "EnterpriseScaleBenchmark",
                "memory_pressure_large_result_sets"
        )

        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        // Allow some time for memory allocation to stabilize
        Thread.sleep(100)
        def memoryAfter = runtime.totalMemory() - runtime.freeMemory()
        def memoryUsed = Math.max(0L, (memoryAfter - memoryBefore)) / 1024 / 1024  // MB

        def benchmarkResult = measurement
                .recordCount(10000000)
                .metadata("query_type", "memory_intensive_large_result_set")
                .metadata("memory_used_mb", memoryUsed)
                .metadata("table", "audit_logs")
                .complete()

        then: "should manage memory efficiently"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.audit_logs').exists())

        and: "should complete within acceptable time"
        benchmarkResult.getDurationMs() < 5000  // 5 second max

        and: "should use reasonable memory"
        memoryUsed < 1024  // Less than 1GB memory increase

        println("✅ Large result set query: ${benchmarkResult.getDurationMs()}ms, Memory used: ${memoryUsed}MB")

        cleanup: "force garbage collection after test"
        System.gc()
    }

    def "should benchmark pagination performance at scale"() {
        given: "pagination through large dataset"
        def queries = [
                '{ employees(first: 100) { edges { node { id first_name } } pageInfo { hasNextPage endCursor } } }',
                '{ employees(first: 1000) { edges { node { id first_name } } pageInfo { hasNextPage endCursor } } }',
                '{ employees(first: 5000) { edges { node { id first_name } } pageInfo { hasNextPage endCursor } } }'
        ]

        when: "testing different pagination sizes"
        def measurement = BenchmarkUtils.startMeasurement(
                "EnterpriseScaleBenchmark",
                "pagination_performance_at_scale"
        )

        def results = []
        queries.eachWithIndex { query, index ->
            def pageSize = [100, 1000, 5000][index]
            def pageStartTime = System.currentTimeMillis()
            
            def result = mockMvc.perform(post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query": "${query}"}"""))
                    .andExpect(status().isOk())
                    .andReturn()
            
            def pageEndTime = System.currentTimeMillis()
            results << [size: pageSize, time: pageEndTime - pageStartTime]
        }

        def benchmarkResult = measurement
                .recordCount(1000000)
                .metadata("query_type", "pagination_performance_testing")
                .metadata("page_sizes_tested", [100, 1000, 5000])
                .metadata("pagination_times", results.collect { "${it.size}:${it.time}ms" })
                .complete()

        then: "pagination should scale linearly"
        results.each { result ->
            assert result.time < (result.size * 2)  // Linear scaling expectation
            println("✅ Pagination ${result.size} records: ${result.time}ms")
        }

        and: "overall benchmark should complete efficiently"
        benchmarkResult.getDurationMs() < 5000  // 5 second max for all pagination tests

        println("✅ Overall pagination benchmark: ${benchmarkResult.getDurationMs()}ms")
    }
}