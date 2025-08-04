-- Excalibase GraphQL Enterprise-Scale Benchmark Database
-- Creates massive enterprise dataset matching EnterpriseScaleBenchmarkTest.groovy
-- Data Scale: 10K companies, 100K departments, 1M employees, 50K projects, 5M time entries, 10M audit logs

-- ====================
-- SCHEMA SETUP
-- ====================
CREATE SCHEMA IF NOT EXISTS hana;
SET search_path TO hana;

-- ====================
-- PERFORMANCE OPTIMIZATIONS FOR MASSIVE DATA INSERTION
-- ====================
-- Note: PostgreSQL server-level parameters are set in docker-compose command
SET synchronous_commit = off;
SET work_mem = '256MB';
SET maintenance_work_mem = '1GB';

-- ====================
-- ENTERPRISE CORE BUSINESS ENTITIES
-- ====================

-- Companies table (10,000 companies)
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

-- Departments table (100,000 departments)
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

-- Employees table (1,000,000 employees) 
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

-- Projects table (50,000 projects)
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

-- Project assignments table
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

-- Time entries table (5,000,000 time entries) - THE BIG ONE
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

-- Financial tables
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

-- Audit logs table (10,000,000 audit logs) - THE MASSIVE ONE  
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

-- Performance metrics table
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

-- Additional data tables (40 tables with 10K records each)
DO $$ 
BEGIN
    FOR i IN 1..40 LOOP
        EXECUTE format('
            CREATE TABLE data_table_%s (
                id SERIAL PRIMARY KEY,
                company_id INTEGER REFERENCES companies(id),
                reference_id INTEGER,
                name VARCHAR(200),
                category VARCHAR(50),
                status VARCHAR(20) DEFAULT ''ACTIVE'',
                priority INTEGER DEFAULT 3,
                data_payload JSONB,
                tags TEXT[],
                numeric_values NUMERIC(10,2)[],
                timestamps TIMESTAMPTZ[],
                config_data JSONB,
                created_at TIMESTAMPTZ DEFAULT NOW(),
                updated_at TIMESTAMPTZ DEFAULT NOW()
            )', i);
    END LOOP;
END $$;

-- ====================
-- ENTERPRISE-SCALE DATA GENERATION
-- ====================

-- Generate 10,000 companies (matching EnterpriseScaleBenchmarkTest)
DO $$
DECLARE
    batch_size INT := 1000;
    total_companies INT := 10000;
    industries TEXT[] := ARRAY['Technology', 'Healthcare', 'Finance', 'Manufacturing', 'Retail', 
                              'Education', 'Energy', 'Transportation', 'Real Estate', 'Media'];
BEGIN
    RAISE NOTICE 'Generating 10,000 companies...';
    
    FOR batch_start IN 0..total_companies-1 BY batch_size LOOP
        INSERT INTO companies (name, industry, founded_year, revenue, employee_count, headquarters, locations)
        SELECT 
            'Company ' || generate_series,
            industries[((generate_series - 1) % array_length(industries, 1)) + 1],
            1950 + (generate_series % 70),
            (generate_series::bigint * 100000) + (generate_series % 50000),
            100 + (generate_series % 5000),
            jsonb_build_object(
                'city', 'City' || (generate_series % 100),
                'country', 'Country' || (generate_series % 50),
                'address', 'Address ' || generate_series
            ),
            ARRAY[jsonb_build_object(
                'city', 'Branch' || (generate_series % 200),
                'country', 'Country' || ((generate_series + 1) % 50)
            )]
        FROM generate_series(batch_start + 1, LEAST(batch_start + batch_size, total_companies));
        
        IF batch_start % 5000 = 0 THEN
            RAISE NOTICE 'Inserted % companies...', batch_start + batch_size;
        END IF;
    END LOOP;
END $$;

-- Generate 100,000 departments (matching EnterpriseScaleBenchmarkTest)
DO $$
DECLARE
    batch_size INT := 5000;
    total_departments INT := 100000;
    dept_names TEXT[] := ARRAY['Engineering', 'Sales', 'Marketing', 'HR', 'Finance',
                               'Operations', 'Legal', 'IT', 'Support', 'Research'];
BEGIN
    RAISE NOTICE 'Generating 100,000 departments...';
    
    FOR batch_start IN 0..total_departments-1 BY batch_size LOOP
        INSERT INTO departments (company_id, name, budget, cost_center, department_metadata)
        SELECT 
            1 + (generate_series % 10000),
            dept_names[((generate_series - 1) % array_length(dept_names, 1)) + 1] || ' ' || generate_series,
            500000 + (generate_series % 1000000),
            'CC-' || generate_series,
            jsonb_build_object(
                'type', 'operational',
                'region', 'Region' || (generate_series % 20),
                'head_count', 10 + (generate_series % 50)
            )
        FROM generate_series(batch_start + 1, LEAST(batch_start + batch_size, total_departments));
        
        IF batch_start % 20000 = 0 THEN
            RAISE NOTICE 'Inserted % departments...', batch_start + batch_size;
        END IF;
    END LOOP;
END $$;

-- Generate 1,000,000 employees (matching EnterpriseScaleBenchmarkTest)
DO $$
DECLARE
    batch_size INT := 10000;
    total_employees INT := 1000000;
    skills TEXT[] := ARRAY['Java', 'Python', 'JavaScript', 'SQL', 'Project Management',
                          'Data Analysis', 'Machine Learning', 'DevOps', 'Marketing', 'Design'];
BEGIN
    RAISE NOTICE 'Generating 1,000,000 employees (this will take a while)...';
    
    FOR batch_start IN 0..total_employees-1 BY batch_size LOOP
        INSERT INTO employees (
            company_id, department_id, employee_number, first_name, last_name, 
            email, salary, employee_level, skills, certifications, address
        )
        SELECT 
            1 + (i % 10000),
            1 + (i % 100000),
            'EMP' || LPAD(i::text, 7, '0'),
            'First' || i,
            'Last' || i,
            'employee' || i || '@company' || (1 + (i % 10000)) || '.com',
            30000 + (i % 150000),
            1 + (i % 10),
            ARRAY[skills[((i - 1) % array_length(skills, 1)) + 1]],
            jsonb_build_object(
                'certs', ARRAY['Cert' || (i % 20), 'Cert' || ((i + 1) % 20)],
                'training_hours', 40 + (i % 200)
            ),
            jsonb_build_object(
                'street', 'Street ' || i,
                'city', 'City' || (i % 100),
                'zip', LPAD(((10000 + (i % 90000))::text), 5, '0')
            )
        FROM generate_series(batch_start + 1, LEAST(batch_start + batch_size, total_employees)) as i;
        
        IF batch_start % 100000 = 0 THEN
            RAISE NOTICE 'Inserted % employees...', batch_start + batch_size;
        END IF;
    END LOOP;
END $$;

-- Generate 50,000 projects (matching EnterpriseScaleBenchmarkTest)
DO $$
DECLARE
    batch_size INT := 5000;
    total_projects INT := 50000;
BEGIN
    RAISE NOTICE 'Generating 50,000 projects...';
    
    FOR batch_start IN 0..total_projects-1 BY batch_size LOOP
        INSERT INTO projects (company_id, name, budget, priority, requirements)
        SELECT 
            1 + (generate_series % 10000),
            'Project ' || generate_series,
            100000 + (generate_series % 5000000),
            1 + (generate_series % 5),
            jsonb_build_object(
                'scope', 'Scope' || generate_series,
                'technologies', ARRAY['Tech' || (generate_series % 10), 'Tech' || ((generate_series + 1) % 10)]
            )
        FROM generate_series(batch_start + 1, LEAST(batch_start + batch_size, total_projects));
    END LOOP;
END $$;

-- Generate 5,000,000 time entries (matching EnterpriseScaleBenchmarkTest - THE BIG ONE)
DO $$
DECLARE
    batch_size INT := 50000;  -- Large batches for performance
    total_entries INT := 5000000;
    activities TEXT[] := ARRAY['Development', 'Testing', 'Analysis', 'Design', 'Meetings',
                              'Documentation', 'Research', 'Planning', 'Review', 'Support'];
BEGIN
    RAISE NOTICE 'Generating 5,000,000 time entries (this is the big one - will take several minutes)...';
    
    FOR batch_start IN 0..total_entries-1 BY batch_size LOOP
        INSERT INTO time_entries (
            employee_id, project_id, entry_date, hours_worked, 
            activity_type, billing_rate, metadata
        )
        SELECT 
            1 + (generate_series % 1000000),
            1 + (generate_series % 50000),
            '2023-01-01'::date + interval '1 day' * (generate_series % 365),
            1 + (generate_series % 12),
            activities[((generate_series - 1) % array_length(activities, 1)) + 1],
            50 + (generate_series % 200),
            jsonb_build_object(
                'project_phase', 'Phase' || (generate_series % 5),
                'complexity', 1 + (generate_series % 5)
            )
        FROM generate_series(batch_start + 1, LEAST(batch_start + batch_size, total_entries));
        
        IF batch_start % 500000 = 0 THEN
            RAISE NOTICE 'Inserted % time entries...', batch_start + batch_size;
        END IF;
    END LOOP;
END $$;

-- Generate 10,000,000 audit logs (matching EnterpriseScaleBenchmarkTest - THE MASSIVE ONE)
DO $$
DECLARE
    batch_size INT := 100000;  -- Very large batches for this massive table
    total_logs INT := 10000000;
    actions TEXT[] := ARRAY['INSERT', 'UPDATE', 'DELETE', 'SELECT'];
BEGIN
    RAISE NOTICE 'Generating 10,000,000 audit logs (this is the massive one - will take longest)...';
    
    FOR batch_start IN 0..total_logs-1 BY batch_size LOOP
        INSERT INTO audit_logs (table_name, record_id, action, old_values, new_values, ip_address, session_data)
        SELECT 
            'table_' || (generate_series % 20),
            generate_series % 1000000,
            actions[((generate_series - 1) % array_length(actions, 1)) + 1],
            jsonb_build_object('old', 'value' || generate_series),
            jsonb_build_object('new', 'value' || (generate_series + 1)),
            ('192.168.' || ((generate_series % 254) + 1) || '.' || ((generate_series % 254) + 1))::inet,
            jsonb_build_object(
                'session_id', 'sess_' || generate_series,
                'duration', generate_series % 3600
            )
        FROM generate_series(batch_start + 1, LEAST(batch_start + batch_size, total_logs));
        
        IF batch_start % 1000000 = 0 THEN
            RAISE NOTICE 'Inserted % audit logs...', batch_start + batch_size;
        END IF;
    END LOOP;
END $$;

-- Generate invoices (100,000)
DO $$
DECLARE
    batch_size INT := 10000;
    total_invoices INT := 100000;
BEGIN
    RAISE NOTICE 'Generating 100,000 invoices...';
    
    FOR batch_start IN 0..total_invoices-1 BY batch_size LOOP
        INSERT INTO invoices (
            company_id, project_id, invoice_number, amount, tax_amount, 
            total_amount, currency, issue_date, due_date, status, line_items, payment_terms
        )
        SELECT 
            1 + (generate_series % 10000),
            1 + (generate_series % 50000),
            'INV-' || LPAD(generate_series::text, 6, '0'),
            10000 + (generate_series % 500000),
            generate_series % 5000,
            10000 + (generate_series % 500000) + (generate_series % 5000),
            'USD',
            '2023-01-01'::date + interval '1 day' * (generate_series % 365),
            '2023-01-01'::date + interval '1 day' * ((generate_series % 365) + 30),
            'PENDING',
            ARRAY[jsonb_build_object('item', 'Service' || (generate_series % 10), 'amount', 1000 + (generate_series % 10000))],
            jsonb_build_object('terms', 'Net 30', 'payment_method', 'ACH')
        FROM generate_series(batch_start + 1, LEAST(batch_start + batch_size, total_invoices));
    END LOOP;
END $$;

-- Generate expenses (500,000)
DO $$
DECLARE
    batch_size INT := 20000;
    total_expenses INT := 500000;
    categories TEXT[] := ARRAY['Travel', 'Meals', 'Office Supplies', 'Software', 'Training',
                              'Equipment', 'Marketing', 'Legal', 'Consulting', 'Utilities'];
BEGIN
    RAISE NOTICE 'Generating 500,000 expenses...';
    
    FOR batch_start IN 0..total_expenses-1 BY batch_size LOOP
        INSERT INTO expenses (
            employee_id, project_id, expense_date, amount, currency, 
            category, description, receipt_url, reimbursable, approved, expense_details
        )
        SELECT 
            1 + (generate_series % 1000000),
            1 + (generate_series % 50000),
            '2023-01-01'::date + interval '1 day' * (generate_series % 365),
            10 + (generate_series % 5000),
            'USD',
            categories[((generate_series - 1) % array_length(categories, 1)) + 1],
            'Expense description ' || generate_series,
            'https://receipts.com/' || generate_series,
            generate_series % 2 = 0,
            generate_series % 3 = 0,
            jsonb_build_object(
                'vendor', 'Vendor' || (generate_series % 100),
                'receipt_number', 'REC-' || generate_series
            )
        FROM generate_series(batch_start + 1, LEAST(batch_start + batch_size, total_expenses));
    END LOOP;
END $$;

-- Generate data for additional tables (40 tables with 10K records each)
DO $$
DECLARE
    batch_size INT := 5000;
    records_per_table INT := 10000;
    table_num INT;
BEGIN
    RAISE NOTICE 'Generating data for 40 additional tables (10K records each)...';
    
    FOR table_num IN 1..40 LOOP
        FOR batch_start IN 0..records_per_table-1 BY batch_size LOOP
            EXECUTE format('
                INSERT INTO data_table_%s (
                    company_id, reference_id, name, category, status, priority,
                    data_payload, tags, numeric_values, timestamps, config_data
                )
                SELECT 
                    1 + (generate_series %% 10000),
                    (%s * %s) + generate_series,
                    ''Record '' || ((%s * %s) + generate_series),
                    ''Category'' || (generate_series %% 20),
                    ''ACTIVE'',
                    1 + (generate_series %% 5),
                    jsonb_build_object(
                        ''table'', %s,
                        ''record'', generate_series,
                        ''metadata'', ''data_'' || ((%s * %s) + generate_series)
                    ),
                    ARRAY[''tag'' || (generate_series %% 10), ''tag'' || ((generate_series + 1) %% 10)],
                    ARRAY[generate_series %% 1000, (generate_series + 1) %% 1000],
                    ARRAY[(''2023-01-01 10:00:00+00''::timestamptz + interval ''1 day'' * (generate_series %% 365))],
                    jsonb_build_object(
                        ''config'', ''value'' || generate_series,
                        ''settings'', jsonb_build_object(''enabled'', (generate_series %% 2 = 0))
                    )
                FROM generate_series(%s, %s)
            ', table_num, table_num, records_per_table, table_num, records_per_table, table_num, 
               table_num, records_per_table, batch_start + 1, LEAST(batch_start + batch_size, records_per_table));
        END LOOP;
        
        IF table_num % 10 = 0 THEN
            RAISE NOTICE 'Completed table data_table_%...', table_num;
        END IF;
    END LOOP;
END $$;

-- ====================
-- ENTERPRISE-GRADE INDEXES FOR PERFORMANCE
-- ====================

DO $$
BEGIN
    RAISE NOTICE 'Creating enterprise-grade indexes...';
END $$;

-- Companies indexes
CREATE INDEX idx_companies_industry ON companies(industry);
CREATE INDEX idx_companies_revenue ON companies(revenue);
CREATE INDEX idx_companies_employee_count ON companies(employee_count);
CREATE INDEX idx_companies_headquarters_gin ON companies USING GIN (headquarters);

-- Departments indexes
CREATE INDEX idx_departments_company_id ON departments(company_id);
CREATE INDEX idx_departments_name ON departments(name);
CREATE INDEX idx_departments_metadata_gin ON departments USING GIN (department_metadata);

-- Employees indexes (critical for 1M records)
CREATE INDEX idx_employees_company_dept ON employees(company_id, department_id);
CREATE INDEX idx_employees_email ON employees(email);
CREATE INDEX idx_employees_salary ON employees(salary);
CREATE INDEX idx_employees_hire_date ON employees(hire_date);
CREATE INDEX idx_employees_level ON employees(employee_level);
CREATE INDEX idx_employees_skills_gin ON employees USING GIN (skills);
CREATE INDEX idx_employees_certifications_gin ON employees USING GIN (certifications);

-- Projects indexes
CREATE INDEX idx_projects_company ON projects(company_id);
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_projects_dates ON projects(start_date, end_date);
CREATE INDEX idx_projects_manager ON projects(project_manager_id);

-- Time entries indexes (CRITICAL for 5M records)
CREATE INDEX idx_time_entries_employee ON time_entries(employee_id);
CREATE INDEX idx_time_entries_project ON time_entries(project_id);
CREATE INDEX idx_time_entries_date ON time_entries(entry_date);
CREATE INDEX idx_time_entries_emp_date ON time_entries(employee_id, entry_date);
CREATE INDEX idx_time_entries_metadata_gin ON time_entries USING GIN (metadata);

-- Audit logs indexes (CRITICAL for 10M records)
CREATE INDEX idx_audit_logs_table_record ON audit_logs(table_name, record_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_ip ON audit_logs(ip_address);

-- Financial table indexes
CREATE INDEX idx_invoices_company ON invoices(company_id);
CREATE INDEX idx_invoices_project ON invoices(project_id);
CREATE INDEX idx_invoices_status ON invoices(status);
CREATE INDEX idx_expenses_employee ON expenses(employee_id);
CREATE INDEX idx_expenses_project ON expenses(project_id);
CREATE INDEX idx_expenses_date ON expenses(expense_date);

-- Performance metrics indexes
CREATE INDEX idx_performance_metrics_entity ON performance_metrics(entity_type, entity_id);
CREATE INDEX idx_performance_metrics_date ON performance_metrics(measurement_date);

-- ====================
-- RESTORE NORMAL SETTINGS
-- ====================
SET synchronous_commit = on;

-- ====================
-- FINAL ANALYSIS FOR OPTIMAL PERFORMANCE
-- ====================
ANALYZE companies;
ANALYZE departments;
ANALYZE employees;
ANALYZE projects;
ANALYZE time_entries;
ANALYZE audit_logs;
ANALYZE invoices;
ANALYZE expenses;
ANALYZE performance_metrics;

-- ====================
-- ENTERPRISE BENCHMARK INITIALIZATION SUMMARY
-- ====================
DO $$
BEGIN
    RAISE NOTICE '================================================================';
    RAISE NOTICE 'ENTERPRISE-SCALE BENCHMARK DATABASE INITIALIZED!';
    RAISE NOTICE '================================================================';
    RAISE NOTICE 'ENTERPRISE CORE TABLES (matching EnterpriseScaleBenchmarkTest):';
    RAISE NOTICE '  - companies: % rows (Target: 10,000)', (SELECT count(*) FROM companies);
    RAISE NOTICE '  - departments: % rows (Target: 100,000)', (SELECT count(*) FROM departments);
    RAISE NOTICE '  - employees: % rows (Target: 1,000,000)', (SELECT count(*) FROM employees);
    RAISE NOTICE '  - projects: % rows (Target: 50,000)', (SELECT count(*) FROM projects);
    RAISE NOTICE '  - time_entries: % rows (Target: 5,000,000)', (SELECT count(*) FROM time_entries);
    RAISE NOTICE '  - audit_logs: % rows (Target: 10,000,000)', (SELECT count(*) FROM audit_logs);
    RAISE NOTICE '';
    RAISE NOTICE 'SUPPORTING TABLES:';
    RAISE NOTICE '  - invoices: % rows', (SELECT count(*) FROM invoices);
    RAISE NOTICE '  - expenses: % rows', (SELECT count(*) FROM expenses);
    RAISE NOTICE '  - performance_metrics: % rows', (SELECT count(*) FROM performance_metrics);
    RAISE NOTICE '';
    RAISE NOTICE 'ADDITIONAL TABLES: 40 tables with 10K records each';
    RAISE NOTICE '';
    RAISE NOTICE 'TOTAL DATABASE SIZE: % MB', (
        SELECT ROUND(
            SUM(pg_total_relation_size(schemaname||'.'||tablename))::numeric / 1024 / 1024, 2
        )
        FROM pg_tables WHERE schemaname = 'hana'
    );
    RAISE NOTICE '';
    RAISE NOTICE 'Schema: hana';
    RAISE NOTICE 'Ready for ENTERPRISE-SCALE GraphQL API benchmarking';
    RAISE NOTICE 'Optimized for millions of records with enterprise-grade indexes';
    RAISE NOTICE '================================================================';
END $$;

-- ====================
-- COMPLETION MARKER (for health check)
-- ====================
CREATE TABLE IF NOT EXISTS hana.initialization_complete (
    completed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status TEXT DEFAULT 'READY'
);

INSERT INTO hana.initialization_complete (status) VALUES ('ENTERPRISE_READY');