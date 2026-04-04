-- Auth schema (used by excalibase-auth)
CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE auth.users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'user',
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    last_login_at TIMESTAMPTZ
);
CREATE INDEX idx_users_email ON auth.users(email);

CREATE TABLE auth.refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(255) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    expiry_date TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    revoked BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_refresh_tokens_token ON auth.refresh_tokens(token);

-- Auth admin role (used by excalibase-auth to connect)
CREATE ROLE auth_admin WITH LOGIN PASSWORD 'authpass';
GRANT USAGE ON SCHEMA auth TO auth_admin;
GRANT ALL ON ALL TABLES IN SCHEMA auth TO auth_admin;
GRANT ALL ON ALL SEQUENCES IN SCHEMA auth TO auth_admin;

-- App schema (used by excalibase-graphql)
CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    product TEXT NOT NULL,
    total NUMERIC(10,2) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO orders (user_id, product, total) VALUES
    (1, 'Laptop', 999.99),
    (1, 'Mouse', 29.99),
    (2, 'Keyboard', 79.99),
    (2, 'Monitor', 449.99),
    (2, 'Headset', 149.99);

-- RLS policies
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;

CREATE POLICY user_orders ON orders
    FOR ALL USING (user_id = current_setting('request.user_id', true)::integer);

-- App user for graphql (non-superuser, so RLS applies)
CREATE ROLE app_user WITH LOGIN PASSWORD 'apppass';
GRANT USAGE ON SCHEMA public TO app_user;
GRANT ALL ON ALL TABLES IN SCHEMA public TO app_user;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO app_user;
