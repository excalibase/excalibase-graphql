CREATE SCHEMA test_rls;

-- Table 1: orders
CREATE TABLE test_rls.orders (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    product TEXT NOT NULL,
    total NUMERIC(10,2) NOT NULL
);

INSERT INTO test_rls.orders (user_id, product, total) VALUES
    (42, 'Widget', 9.99),
    (42, 'Gadget', 19.99),
    (99, 'Other', 5.00);

ALTER TABLE test_rls.orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_rls.orders FORCE ROW LEVEL SECURITY;

CREATE POLICY user_orders ON test_rls.orders
    FOR ALL USING (user_id = current_setting('request.user_id', true)::integer);

-- Table 2: payments (second RLS table)
CREATE TABLE test_rls.payments (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    amount NUMERIC(10,2) NOT NULL,
    method TEXT NOT NULL
);

INSERT INTO test_rls.payments (user_id, amount, method) VALUES
    (42, 9.99, 'card'),
    (42, 19.99, 'card'),
    (42, 5.00, 'paypal'),
    (99, 15.00, 'card');

ALTER TABLE test_rls.payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_rls.payments FORCE ROW LEVEL SECURITY;

CREATE POLICY user_payments ON test_rls.payments
    FOR ALL USING (user_id = current_setting('request.user_id', true)::integer);

-- Non-superuser (superusers bypass RLS)
CREATE ROLE app_user WITH LOGIN PASSWORD 'apppass';
GRANT USAGE ON SCHEMA test_rls TO app_user;
GRANT ALL ON ALL TABLES IN SCHEMA test_rls TO app_user;
GRANT ALL ON ALL SEQUENCES IN SCHEMA test_rls TO app_user;
