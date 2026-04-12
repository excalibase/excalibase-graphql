-- Study-cases: kanban postgres user setup
-- Runs before initdb-kanban.sql (alphabetical order: 01 before 02)

CREATE SCHEMA IF NOT EXISTS kanban;
CREATE SCHEMA IF NOT EXISTS auth;

-- App role: used by excalibase-graphql to serve GraphQL/REST queries
-- app_user is the name expected by initdb-kanban.sql grants
CREATE USER app_user WITH PASSWORD 'kanban_app_pass';
CREATE USER excalibase_app WITH PASSWORD 'kanban_app_pass';
GRANT USAGE ON SCHEMA kanban TO excalibase_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA kanban TO excalibase_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA kanban TO excalibase_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA kanban GRANT ALL PRIVILEGES ON TABLES TO excalibase_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA kanban GRANT USAGE, SELECT ON SEQUENCES TO excalibase_app;

-- Auth role: used by excalibase-auth to manage auth.users and auth.refresh_tokens
CREATE USER auth_admin WITH PASSWORD 'kanban_auth_pass';
GRANT CREATE ON DATABASE kanban_db TO auth_admin;
GRANT ALL ON SCHEMA auth TO auth_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA auth GRANT ALL ON TABLES TO auth_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA auth GRANT ALL ON SEQUENCES TO auth_admin;
