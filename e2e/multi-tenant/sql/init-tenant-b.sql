CREATE SCHEMA IF NOT EXISTS tenant;
CREATE SCHEMA IF NOT EXISTS auth;

-- App role (used by excalibase-graphql)
CREATE USER excalibase_app WITH PASSWORD 'app_pass_b';
GRANT USAGE ON SCHEMA tenant TO excalibase_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA tenant TO excalibase_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA tenant GRANT ALL PRIVILEGES ON TABLES TO excalibase_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA tenant TO excalibase_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA tenant GRANT USAGE, SELECT ON SEQUENCES TO excalibase_app;

-- Auth role (used by excalibase-auth for user storage and migration)
CREATE USER auth_admin WITH PASSWORD 'auth_pass_b';
GRANT CREATE ON DATABASE tenant_b_db TO auth_admin;
GRANT ALL ON SCHEMA auth TO auth_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA auth GRANT ALL ON TABLES TO auth_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA auth GRANT ALL ON SEQUENCES TO auth_admin;

-- Tenant B data: items table (different from tenant A)
CREATE TABLE tenant.items (
    id SERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    quantity INTEGER NOT NULL
);

INSERT INTO tenant.items (title, quantity) VALUES
    ('Item X', 10),
    ('Item Y', 20);
