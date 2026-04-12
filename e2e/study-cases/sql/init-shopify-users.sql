-- Study-cases: shopify postgres user setup
-- Runs before initdb-ecommerce.sql (alphabetical order: 01 before 02)

CREATE SCHEMA IF NOT EXISTS shopify;
CREATE SCHEMA IF NOT EXISTS auth;

-- App role: used by excalibase-graphql to serve GraphQL/REST queries
-- app_user is the name expected by initdb-ecommerce.sql grants
CREATE USER app_user WITH PASSWORD 'shopify_app_pass';
CREATE USER excalibase_app WITH PASSWORD 'shopify_app_pass';
GRANT USAGE ON SCHEMA shopify TO excalibase_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA shopify TO excalibase_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA shopify TO excalibase_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA shopify GRANT ALL PRIVILEGES ON TABLES TO excalibase_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA shopify GRANT USAGE, SELECT ON SEQUENCES TO excalibase_app;

-- Auth role: used by excalibase-auth to manage auth.users and auth.refresh_tokens
CREATE USER auth_admin WITH PASSWORD 'shopify_auth_pass';
GRANT CREATE ON DATABASE shopify_db TO auth_admin;
GRANT ALL ON SCHEMA auth TO auth_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA auth GRANT ALL ON TABLES TO auth_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA auth GRANT ALL ON SEQUENCES TO auth_admin;
