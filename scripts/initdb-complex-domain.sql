-- Complex Domain Database for Edge Case Testing
-- Tests: self-ref FK, deep chains, multi-FK, many-to-many, reserved words,
--        no-PK tables, circular FK, nullable FK, large text, composite PK

-- ============================================================================
-- Schema: complex
-- ============================================================================
CREATE SCHEMA IF NOT EXISTS complex;
SET search_path TO complex;

-- ============================================================================
-- ENUMS
-- ============================================================================
CREATE TYPE employment_status AS ENUM ('active', 'on_leave', 'terminated', 'contractor');
CREATE TYPE project_status AS ENUM ('planning', 'in_progress', 'on_hold', 'completed', 'cancelled');
CREATE TYPE priority AS ENUM ('low', 'medium', 'high', 'critical');
CREATE TYPE task_status AS ENUM ('todo', 'in_progress', 'review', 'done', 'blocked');

-- ============================================================================
-- 1. DEEP FK CHAIN (6 levels): company → department → team → employee → task → comment
-- ============================================================================

CREATE TABLE company (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    founded_at DATE,
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE department (
    id SERIAL PRIMARY KEY,
    company_id INTEGER NOT NULL REFERENCES company(id),
    name VARCHAR(200) NOT NULL,
    budget NUMERIC(15,2),
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE team (
    id SERIAL PRIMARY KEY,
    department_id INTEGER NOT NULL REFERENCES department(id),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 2. SELF-REFERENTIAL FK + NULLABLE FK + MULTIPLE FKs TO SAME TABLE
CREATE TABLE employee (
    id SERIAL PRIMARY KEY,
    team_id INTEGER NOT NULL REFERENCES team(id),
    manager_id INTEGER REFERENCES employee(id),          -- SELF-REF FK
    mentor_id INTEGER REFERENCES employee(id),           -- SECOND SELF-REF FK (NULLABLE)
    name VARCHAR(200) NOT NULL,
    email VARCHAR(200) UNIQUE,
    status employment_status NOT NULL DEFAULT 'active',
    hire_date DATE NOT NULL DEFAULT CURRENT_DATE,
    salary NUMERIC(12,2),
    profile JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 3. MULTIPLE FKs FROM SAME TABLE TO SAME TABLE (task → employee x2)
CREATE TABLE task (
    id SERIAL PRIMARY KEY,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    assignee_id INTEGER NOT NULL REFERENCES employee(id),  -- FK #1 to employee
    reporter_id INTEGER NOT NULL REFERENCES employee(id),  -- FK #2 to employee
    status task_status NOT NULL DEFAULT 'todo',
    priority priority NOT NULL DEFAULT 'medium',
    due_date DATE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE comment (
    id SERIAL PRIMARY KEY,
    task_id INTEGER NOT NULL REFERENCES task(id),
    author_id INTEGER NOT NULL REFERENCES employee(id),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- ============================================================================
-- 4. MANY-TO-MANY via junction table with COMPOSITE PK + extra columns
-- ============================================================================

CREATE TABLE project (
    id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    status project_status NOT NULL DEFAULT 'planning',
    start_date DATE,
    end_date DATE,
    budget NUMERIC(15,2),
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE project_member (
    project_id INTEGER NOT NULL REFERENCES project(id),
    employee_id INTEGER NOT NULL REFERENCES employee(id),
    role VARCHAR(50) NOT NULL DEFAULT 'member',
    joined_at DATE NOT NULL DEFAULT CURRENT_DATE,
    PRIMARY KEY (project_id, employee_id)
);

-- ============================================================================
-- 5. RESERVED SQL WORDS as table and column names
-- ============================================================================

CREATE TABLE "order" (
    id SERIAL PRIMARY KEY,
    "group" VARCHAR(100),
    "select" BOOLEAN DEFAULT false,
    "from" TIMESTAMPTZ,
    "where" TEXT,
    "limit" INTEGER,
    "table" VARCHAR(100),
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- ============================================================================
-- 6. TABLE WITH NO PRIMARY KEY
-- ============================================================================

CREATE TABLE audit_log (
    event_type VARCHAR(100) NOT NULL,
    table_name VARCHAR(100),
    record_id INTEGER,
    payload JSONB,
    actor_id INTEGER,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- ============================================================================
-- 7. CIRCULAR FOREIGN KEYS (deferred constraints)
-- ============================================================================

CREATE TABLE config_a (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    ref_b_id INTEGER   -- FK added after config_b exists
);

CREATE TABLE config_b (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    ref_a_id INTEGER REFERENCES config_a(id)
);

ALTER TABLE config_a ADD CONSTRAINT fk_config_a_ref_b FOREIGN KEY (ref_b_id) REFERENCES config_b(id);

-- ============================================================================
-- 8. LARGE TEXT + JSONB document
-- ============================================================================

CREATE TABLE document (
    id SERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    content TEXT,                -- will insert 100KB+ text
    metadata JSONB,
    tags TEXT[],
    word_count INTEGER,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- ============================================================================
-- 9. VIEWS (simple + multi-join)
-- ============================================================================

CREATE VIEW active_employees AS
    SELECT e.id, e.name, e.email, e.status, e.hire_date, e.salary,
           t.name AS team_name, d.name AS department_name, c.name AS company_name
    FROM employee e
    JOIN team t ON t.id = e.team_id
    JOIN department d ON d.id = t.department_id
    JOIN company c ON c.id = d.company_id
    WHERE e.status = 'active';

CREATE VIEW task_overview AS
    SELECT t.id, t.title, t.status, t.priority, t.due_date,
           a.name AS assignee_name, r.name AS reporter_name
    FROM task t
    JOIN employee a ON a.id = t.assignee_id
    JOIN employee r ON r.id = t.reporter_id;

CREATE MATERIALIZED VIEW project_summary AS
    SELECT p.id, p.name, p.status, p.budget,
           COUNT(pm.employee_id) AS member_count
    FROM project p
    LEFT JOIN project_member pm ON pm.project_id = p.id
    GROUP BY p.id, p.name, p.status, p.budget;

-- ============================================================================
-- 10. COMPUTED FIELDS (functions taking table row as argument)
-- ============================================================================

CREATE OR REPLACE FUNCTION employee_full_title(e employee)
RETURNS TEXT AS $$
    SELECT e.name || ' (' || e.status || ')';
$$ LANGUAGE SQL STABLE;

CREATE OR REPLACE FUNCTION employee_tenure_days(e employee)
RETURNS INTEGER AS $$
    SELECT (CURRENT_DATE - e.hire_date);
$$ LANGUAGE SQL STABLE;

CREATE OR REPLACE FUNCTION task_is_overdue(t task)
RETURNS BOOLEAN AS $$
    SELECT t.due_date IS NOT NULL AND t.due_date < CURRENT_DATE AND t.status != 'done';
$$ LANGUAGE SQL STABLE;

-- ============================================================================
-- SEED DATA
-- ============================================================================

-- Companies
INSERT INTO company (name, founded_at, metadata) VALUES
    ('Acme Corp', '2010-01-15', '{"industry": "tech", "size": "large"}'),
    ('Globex Inc', '2015-06-20', '{"industry": "finance", "size": "medium"}');

-- Departments
INSERT INTO department (company_id, name, budget) VALUES
    (1, 'Engineering', 500000.00),
    (1, 'Marketing', 200000.00),
    (2, 'Research', 300000.00);

-- Teams
INSERT INTO team (department_id, name) VALUES
    (1, 'Backend'),
    (1, 'Frontend'),
    (2, 'Growth'),
    (3, 'Data Science');

-- Employees (with self-referential manager + nullable mentor)
INSERT INTO employee (team_id, manager_id, mentor_id, name, email, status, hire_date, salary, profile) VALUES
    (1, NULL, NULL, 'Alice CEO', 'alice@acme.com', 'active', '2010-01-15', 250000.00, '{"level": "C-suite"}'),
    (1, 1, NULL, 'Bob VP Eng', 'bob@acme.com', 'active', '2011-03-01', 200000.00, '{"level": "VP"}'),
    (1, 2, 1, 'Charlie Lead', 'charlie@acme.com', 'active', '2015-06-15', 150000.00, '{"level": "Lead"}'),
    (1, 3, 2, 'Diana Dev', 'diana@acme.com', 'active', '2020-01-10', 120000.00, '{"level": "Senior"}'),
    (2, 2, NULL, 'Eve Frontend', 'eve@acme.com', 'active', '2021-04-01', 110000.00, NULL),
    (3, NULL, NULL, 'Frank Marketing', 'frank@acme.com', 'on_leave', '2018-09-01', 95000.00, NULL),
    (4, NULL, 1, 'Grace DS', 'grace@globex.com', 'active', '2022-01-15', 140000.00, '{"level": "Senior"}'),
    (4, 7, NULL, 'Hank Junior', 'hank@globex.com', 'contractor', '2024-06-01', 80000.00, NULL);

-- Tasks (multiple FKs to employee: assignee + reporter)
INSERT INTO task (title, description, assignee_id, reporter_id, status, priority, due_date) VALUES
    ('Implement auth', 'Add JWT authentication', 4, 3, 'in_progress', 'high', '2026-04-15'),
    ('Fix pagination', 'Cursor pagination broken', 4, 2, 'todo', 'critical', '2026-04-10'),
    ('Design landing page', 'New marketing site', 5, 6, 'review', 'medium', '2026-05-01'),
    ('ML model training', 'Train recommendation model', 7, 7, 'in_progress', 'high', NULL),
    ('Code review', 'Review PR #42', 3, 4, 'done', 'low', '2026-03-30'),
    ('Overdue task', 'This was due yesterday', 8, 7, 'todo', 'medium', '2026-01-01');

-- Comments
INSERT INTO comment (task_id, author_id, content) VALUES
    (1, 3, 'Started working on this'),
    (1, 4, 'JWT library chosen: nimbus-jose'),
    (1, 2, 'Looks good, keep going'),
    (2, 4, 'Found the root cause'),
    (2, 2, 'Priority bumped to critical'),
    (5, 4, 'LGTM, approved');

-- Projects + many-to-many members
INSERT INTO project (name, status, start_date, end_date, budget) VALUES
    ('Platform Rewrite', 'in_progress', '2026-01-01', '2026-12-31', 1000000.00),
    ('Marketing Campaign', 'planning', '2026-04-01', '2026-06-30', 50000.00),
    ('ML Pipeline', 'in_progress', '2026-02-01', NULL, 200000.00);

INSERT INTO project_member (project_id, employee_id, role, joined_at) VALUES
    (1, 2, 'lead', '2026-01-01'),
    (1, 3, 'member', '2026-01-15'),
    (1, 4, 'member', '2026-01-15'),
    (1, 5, 'member', '2026-02-01'),
    (2, 6, 'lead', '2026-04-01'),
    (2, 5, 'member', '2026-04-01'),
    (3, 7, 'lead', '2026-02-01'),
    (3, 8, 'member', '2026-06-01');

-- Reserved word table
INSERT INTO "order" ("group", "select", "from", "where", "limit", "table", description) VALUES
    ('admin', true, '2026-01-01', 'status = active', 100, 'users', 'First order'),
    ('user', false, '2026-02-15', NULL, 50, 'products', 'Second order'),
    ('admin', true, '2026-03-20', 'priority = high', NULL, NULL, NULL);

-- Audit log (no PK)
INSERT INTO audit_log (event_type, table_name, record_id, payload, actor_id) VALUES
    ('INSERT', 'employee', 4, '{"name": "Diana Dev"}', 2),
    ('UPDATE', 'task', 1, '{"status": "in_progress"}', 4),
    ('DELETE', 'comment', 99, '{"reason": "spam"}', 1),
    ('INSERT', 'project', 1, '{"name": "Platform Rewrite"}', 2);

-- Circular FK data
INSERT INTO config_a (name) VALUES ('Config Alpha'), ('Config Beta');
INSERT INTO config_b (name, ref_a_id) VALUES ('Config One', 1), ('Config Two', 2);
UPDATE config_a SET ref_b_id = 1 WHERE id = 1;
UPDATE config_a SET ref_b_id = 2 WHERE id = 2;

-- Documents (large text)
INSERT INTO document (title, content, metadata, tags, word_count) VALUES
    ('Short Doc', 'Brief content.', '{"type": "note"}', ARRAY['quick', 'test'], 2),
    ('Medium Doc', repeat('Lorem ipsum dolor sit amet. ', 100), '{"type": "article"}', ARRAY['lorem', 'test'], 600),
    ('Large Doc', repeat('This is a large document with many words to test text handling. ', 2000), '{"type": "report", "pages": 50}', ARRAY['large', 'performance', 'test'], 24000);

-- Refresh materialized view
REFRESH MATERIALIZED VIEW project_summary;

-- ============================================================================
-- Grant access to app_user (non-superuser for RLS)
-- ============================================================================
GRANT USAGE ON SCHEMA complex TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA complex TO app_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA complex TO app_user;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA complex TO app_user;
