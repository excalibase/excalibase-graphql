CREATE SCHEMA IF NOT EXISTS tenant;
CREATE SCHEMA IF NOT EXISTS auth;

-- App role (used by excalibase-graphql)
CREATE USER excalibase_app WITH PASSWORD 'app_pass_a';
GRANT USAGE ON SCHEMA tenant TO excalibase_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA tenant TO excalibase_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA tenant GRANT ALL PRIVILEGES ON TABLES TO excalibase_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA tenant TO excalibase_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA tenant GRANT USAGE, SELECT ON SEQUENCES TO excalibase_app;

-- Auth role (used by excalibase-auth for user storage and migration)
CREATE USER auth_admin WITH PASSWORD 'auth_pass_a';
GRANT CREATE ON DATABASE tenant_a_db TO auth_admin;
GRANT ALL ON SCHEMA auth TO auth_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA auth GRANT ALL ON TABLES TO auth_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA auth GRANT ALL ON SEQUENCES TO auth_admin;

-- Tenant A data: products table
CREATE TABLE tenant.products (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    price NUMERIC(10,2) NOT NULL
);

INSERT INTO tenant.products (name, price) VALUES
    ('Widget A', 9.99),
    ('Gadget A', 19.99),
    ('Gizmo A', 29.99);
