CREATE SCHEMA IF NOT EXISTS test_schema;

CREATE TABLE test_schema.customer (
    customer_id SERIAL PRIMARY KEY,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    email VARCHAR(100),
    active BOOLEAN DEFAULT true
);

CREATE TABLE test_schema.orders (
    order_id SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES test_schema.customer(customer_id),
    total_amount NUMERIC(10,2) NOT NULL,
    order_date DATE DEFAULT CURRENT_DATE
);

CREATE TABLE test_schema.order_items (
    order_id INTEGER REFERENCES test_schema.orders(order_id),
    product_id INTEGER NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    price NUMERIC(10,2) NOT NULL,
    PRIMARY KEY (order_id, product_id)
);

-- Seed data
INSERT INTO test_schema.customer (first_name, last_name, email, active) VALUES
    ('Alice', 'Smith', 'alice@example.com', true),
    ('Bob', 'Jones', 'bob@example.com', true),
    ('Carol', 'Williams', 'carol@example.com', false),
    ('David', 'Brown', 'david@example.com', true),
    ('Eve', 'Davis', 'eve@example.com', true);

INSERT INTO test_schema.orders (customer_id, total_amount) VALUES
    (1, 100.50),
    (1, 250.00),
    (2, 75.25),
    (3, 300.00),
    (4, 50.00);

INSERT INTO test_schema.order_items (order_id, product_id, quantity, price) VALUES
    (1, 1, 2, 25.25),
    (1, 2, 1, 50.00),
    (2, 3, 5, 50.00),
    (3, 1, 1, 75.25),
    (4, 4, 3, 100.00);

-- Enum type
CREATE TYPE test_schema.priority_level AS ENUM ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');

CREATE TABLE test_schema.task (
    task_id SERIAL PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    priority test_schema.priority_level DEFAULT 'MEDIUM',
    assigned_to INTEGER REFERENCES test_schema.customer(customer_id)
);

INSERT INTO test_schema.task (title, priority, assigned_to) VALUES
    ('Fix bug', 'HIGH', 1),
    ('Write docs', 'LOW', 2),
    ('Deploy', 'CRITICAL', 1);

-- Computed field function
CREATE OR REPLACE FUNCTION test_schema.customer_full_name(test_schema.customer)
RETURNS TEXT AS $$
    SELECT $1.first_name || ' ' || $1.last_name;
$$ LANGUAGE SQL STABLE;

-- View
CREATE VIEW test_schema.active_customers AS
    SELECT customer_id, first_name, last_name, email
    FROM test_schema.customer WHERE active = true;

-- JSONB column
ALTER TABLE test_schema.customer ADD COLUMN metadata JSONB DEFAULT '{}'::jsonb;
UPDATE test_schema.customer SET metadata = '{"vip": true}' WHERE customer_id = 1;
UPDATE test_schema.customer SET metadata = '{"vip": false}' WHERE customer_id = 2;

-- Array column
ALTER TABLE test_schema.customer ADD COLUMN tags TEXT[] DEFAULT '{}';
UPDATE test_schema.customer SET tags = ARRAY['premium', 'early-adopter'] WHERE customer_id = 1;
UPDATE test_schema.customer SET tags = ARRAY['standard'] WHERE customer_id = 2;
