CREATE SCHEMA kanban;
GRANT USAGE ON SCHEMA kanban TO app_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA kanban TO app_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA kanban GRANT ALL PRIVILEGES ON TABLES TO app_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA kanban TO app_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA kanban GRANT USAGE, SELECT ON SEQUENCES TO app_user;

CREATE TYPE kanban.org_plan AS ENUM ('free', 'pro', 'enterprise');
CREATE TYPE kanban.user_role AS ENUM ('admin', 'member', 'viewer');
CREATE TYPE kanban.sprint_status AS ENUM ('planning', 'active', 'completed');
CREATE TYPE kanban.issue_priority AS ENUM ('critical', 'high', 'medium', 'low');
CREATE TYPE kanban.issue_status AS ENUM ('backlog', 'todo', 'in_progress', 'review', 'done');

CREATE TABLE kanban.organizations (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    slug TEXT UNIQUE NOT NULL,
    plan kanban.org_plan DEFAULT 'free'
);

CREATE TABLE kanban.users (
    id SERIAL PRIMARY KEY,
    org_id INTEGER NOT NULL REFERENCES kanban.organizations(id),
    email TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    role kanban.user_role DEFAULT 'member',
    avatar_url TEXT,
    active BOOLEAN DEFAULT true
);

CREATE TABLE kanban.projects (
    id SERIAL PRIMARY KEY,
    org_id INTEGER NOT NULL REFERENCES kanban.organizations(id),
    name TEXT NOT NULL,
    key TEXT UNIQUE NOT NULL,
    description TEXT,
    archived BOOLEAN DEFAULT false
);

CREATE TABLE kanban.sprints (
    id SERIAL PRIMARY KEY,
    project_id INTEGER NOT NULL REFERENCES kanban.projects(id),
    name TEXT NOT NULL,
    start_date DATE,
    end_date DATE,
    goal TEXT,
    status kanban.sprint_status DEFAULT 'planning',
    CHECK (start_date < end_date)
);

CREATE TABLE kanban.labels (
    id SERIAL PRIMARY KEY,
    project_id INTEGER NOT NULL REFERENCES kanban.projects(id),
    name TEXT NOT NULL,
    color TEXT DEFAULT '#ccc'
);

CREATE TABLE kanban.issues (
    id SERIAL PRIMARY KEY,
    project_id INTEGER NOT NULL REFERENCES kanban.projects(id),
    sprint_id INTEGER REFERENCES kanban.sprints(id),
    parent_issue_id INTEGER REFERENCES kanban.issues(id),
    assignee_id INTEGER REFERENCES kanban.users(id),
    reporter_id INTEGER NOT NULL REFERENCES kanban.users(id),
    title TEXT NOT NULL,
    description TEXT,
    priority kanban.issue_priority DEFAULT 'medium',
    status kanban.issue_status DEFAULT 'backlog',
    story_points INTEGER,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE kanban.issue_labels (
    issue_id INTEGER NOT NULL REFERENCES kanban.issues(id),
    label_id INTEGER NOT NULL REFERENCES kanban.labels(id),
    PRIMARY KEY (issue_id, label_id)
);

CREATE TABLE kanban.comments (
    id SERIAL PRIMARY KEY,
    issue_id INTEGER NOT NULL REFERENCES kanban.issues(id),
    author_id INTEGER NOT NULL REFERENCES kanban.users(id),
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE kanban.time_entries (
    id SERIAL PRIMARY KEY,
    issue_id INTEGER NOT NULL REFERENCES kanban.issues(id),
    user_id INTEGER NOT NULL REFERENCES kanban.users(id),
    hours NUMERIC(5,2) NOT NULL,
    description TEXT,
    logged_at DATE DEFAULT CURRENT_DATE
);

CREATE TABLE kanban.attachments (
    id SERIAL PRIMARY KEY,
    issue_id INTEGER NOT NULL REFERENCES kanban.issues(id),
    uploader_id INTEGER NOT NULL REFERENCES kanban.users(id),
    filename TEXT NOT NULL,
    url TEXT NOT NULL,
    size_bytes BIGINT,
    uploaded_at TIMESTAMPTZ DEFAULT now()
);

CREATE VIEW kanban.sprint_board AS
    SELECT i.id, i.title, i.status, i.priority, i.story_points,
           u.name AS assignee_name, s.name AS sprint_name
    FROM kanban.issues i
    LEFT JOIN kanban.users u ON u.id = i.assignee_id
    JOIN kanban.sprints s ON s.id = i.sprint_id
    WHERE s.status = 'active';

CREATE VIEW kanban.user_workload AS
    SELECT u.id, u.name, u.email,
           COUNT(i.id) AS assigned_issues,
           COALESCE(SUM(i.story_points), 0) AS total_points
    FROM kanban.users u
    LEFT JOIN kanban.issues i ON i.assignee_id = u.id AND i.status != 'done'
    GROUP BY u.id, u.name, u.email;

-- Computed field: issue age in days
CREATE OR REPLACE FUNCTION kanban.issue_age(kanban.issues)
RETURNS INTEGER LANGUAGE sql STABLE AS $$
    SELECT EXTRACT(DAY FROM now() - $1.created_at)::integer;
$$;

-- Seed data
INSERT INTO kanban.organizations (name, slug, plan) VALUES
    ('Acme Corp', 'acme', 'enterprise'),
    ('Startup Inc', 'startup', 'pro');

INSERT INTO kanban.users (org_id, email, name, role) VALUES
    (1, 'alice@acme.com', 'Alice Chen', 'admin'),
    (1, 'bob@acme.com', 'Bob Kumar', 'member'),
    (1, 'carol@acme.com', 'Carol Park', 'member'),
    (2, 'dave@startup.com', 'Dave Lee', 'admin'),
    (2, 'eve@startup.com', 'Eve Torres', 'member'),
    (2, 'frank@startup.com', 'Frank Nguyen', 'viewer');

INSERT INTO kanban.projects (org_id, name, key, description) VALUES
    (1, 'Platform API', 'PLAT', 'Core platform services'),
    (1, 'Mobile App', 'MOB', 'iOS and Android app'),
    (2, 'MVP Launch', 'MVP', 'Minimum viable product');

INSERT INTO kanban.sprints (project_id, name, start_date, end_date, goal, status) VALUES
    (1, 'Sprint 1', '2026-04-01', '2026-04-14', 'Auth + Users', 'completed'),
    (1, 'Sprint 2', '2026-04-15', '2026-04-28', 'REST API', 'active'),
    (2, 'Sprint 1', '2026-04-01', '2026-04-14', 'UI Foundation', 'active'),
    (3, 'Sprint 1', '2026-04-01', '2026-04-30', 'Core Features', 'active');

INSERT INTO kanban.labels (project_id, name, color) VALUES
    (1, 'bug', '#ff0000'),
    (1, 'feature', '#00ff00'),
    (1, 'tech-debt', '#ffaa00'),
    (2, 'bug', '#ff0000'),
    (2, 'design', '#0000ff'),
    (3, 'mvp', '#9900ff');

INSERT INTO kanban.issues (project_id, sprint_id, parent_issue_id, assignee_id, reporter_id, title, priority, status, story_points) VALUES
    (1, 1, NULL, 1, 2, 'Setup JWT auth', 'high', 'done', 5),
    (1, 1, NULL, 2, 1, 'User CRUD endpoints', 'high', 'done', 8),
    (1, 2, NULL, 1, 2, 'REST filter operators', 'critical', 'in_progress', 13),
    (1, 2, 3, 2, 1, 'Implement eq/neq operators', 'high', 'done', 3),
    (1, 2, 3, 3, 1, 'Implement range operators', 'medium', 'in_progress', 5),
    (1, 2, NULL, NULL, 2, 'OpenAPI generation', 'medium', 'backlog', 8),
    (1, NULL, NULL, NULL, 1, 'Performance benchmarks', 'low', 'backlog', NULL),
    (2, 3, NULL, 2, 1, 'Login screen', 'high', 'in_progress', 5),
    (2, 3, NULL, 3, 1, 'Dashboard layout', 'medium', 'todo', 8),
    (2, 3, 8, 2, 1, 'Social login buttons', 'low', 'backlog', 3),
    (3, 4, NULL, 4, 5, 'Landing page', 'high', 'in_progress', 5),
    (3, 4, NULL, 5, 4, 'Payment integration', 'critical', 'todo', 13),
    (3, 4, NULL, 4, 5, 'Email notifications', 'medium', 'backlog', 5),
    (3, NULL, NULL, NULL, 4, 'Analytics dashboard', 'low', 'backlog', 21),
    (3, 4, 12, 5, 4, 'Stripe webhook handler', 'high', 'todo', 8);

INSERT INTO kanban.issue_labels (issue_id, label_id) VALUES
    (1, 2), (2, 2), (3, 2), (4, 2), (5, 2),
    (6, 2), (7, 3), (8, 2), (8, 5), (9, 5),
    (10, 2), (11, 6), (12, 6), (13, 6), (14, 6),
    (15, 6), (3, 1), (5, 3), (12, 2), (15, 2);

INSERT INTO kanban.comments (issue_id, author_id, body) VALUES
    (1, 2, 'Using JJWT library for this'),
    (1, 1, 'Done, merged to main'),
    (3, 1, 'Starting with PostgREST filter syntax'),
    (3, 2, 'Need to handle negation too'),
    (3, 3, 'What about array operators?'),
    (8, 1, 'Use the new design system components'),
    (8, 2, 'Working on it, ETA tomorrow'),
    (11, 5, 'Landing page copy is ready'),
    (12, 4, 'We should support Stripe and PayPal'),
    (12, 5, 'Stripe first, PayPal in v2'),
    (15, 4, 'Webhook endpoint: /api/webhooks/stripe'),
    (15, 5, 'Need to verify webhook signatures');

INSERT INTO kanban.time_entries (issue_id, user_id, hours, description) VALUES
    (1, 1, 4.0, 'JWT implementation'),
    (1, 1, 2.5, 'Testing and fixes'),
    (2, 2, 6.0, 'CRUD endpoints'),
    (3, 1, 8.0, 'Filter framework'),
    (4, 2, 3.0, 'Eq/neq operators'),
    (5, 3, 4.0, 'Range operators'),
    (8, 2, 5.0, 'Login UI'),
    (11, 4, 3.0, 'Landing page design');

INSERT INTO kanban.attachments (issue_id, uploader_id, filename, url, size_bytes) VALUES
    (8, 2, 'login-mockup.png', 'https://cdn.example.com/login-mockup.png', 245000),
    (9, 3, 'dashboard-wireframe.pdf', 'https://cdn.example.com/dashboard.pdf', 1200000),
    (11, 4, 'landing-v2.fig', 'https://cdn.example.com/landing-v2.fig', 5400000),
    (12, 5, 'stripe-docs.md', 'https://cdn.example.com/stripe-docs.md', 12000);
