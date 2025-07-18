-- Excalibase GraphQL E2E Test Database Initialization
-- This script creates sample tables and data for end-to-end testing
-- Based on test data from GraphqlControllerTest.groovy

-- Create schema
CREATE SCHEMA IF NOT EXISTS public;
SET search_path TO public;

-- ====================
-- CUSTOMER TABLE (Basic Testing)
-- ====================
CREATE TABLE IF NOT EXISTS customer (
    customer_id SERIAL PRIMARY KEY,
    first_name VARCHAR(45) NOT NULL,
    last_name VARCHAR(45) NOT NULL,
    email VARCHAR(50),
    active BOOLEAN DEFAULT true,
    create_date DATE NOT NULL DEFAULT CURRENT_DATE,
    last_update TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Insert customer sample data
INSERT INTO customer (customer_id, first_name, last_name, email, active, create_date, last_update) VALUES
(1, 'MARY', 'SMITH', 'mary.smith@example.com', true, '2006-02-14', '2013-05-26 14:49:45'),
(2, 'PATRICIA', 'JOHNSON', 'patricia.johnson@example.com', true, '2006-02-14', '2013-05-26 14:49:45'),
(3, 'LINDA', 'WILLIAMS', 'linda.williams@example.com', true, '2006-02-14', '2013-05-26 14:49:45'),
(4, 'BARBARA', 'JONES', 'barbara.jones@example.com', false, '2006-02-14', '2013-05-26 14:49:45'),
(5, 'ELIZABETH', 'BROWN', 'elizabeth.brown@example.com', true, '2006-02-14', '2013-05-26 14:49:45'),
(6, 'JENNIFER', 'DAVIS', null, true, '2006-02-15', '2013-05-26 14:49:45'),
(7, 'MARIA', 'MILLER', null, false, '2006-02-15', '2013-05-26 14:49:45'),
(8, 'SUSAN', 'WILSON', 'susan.wilson@example.com', true, '2006-02-15', '2013-05-26 14:49:45'),
(9, 'MARGARET', 'MOORE', 'margaret.moore@example.com', true, '2006-02-15', '2013-05-26 14:49:45'),
(10, 'DOROTHY', 'TAYLOR', 'dorothy.taylor@example.com', false, '2006-02-15', '2013-05-26 14:49:45'),
(11, 'MARY', 'SMITHSON', 'mary.smithson@example.com', true, '2007-01-01', '2013-05-26 14:49:45'),
(12, 'JOHN', 'SMITH', 'john.smith@example.com', true, '2007-01-01', '2013-05-26 14:49:45');

-- ====================
-- ENHANCED TYPES TABLE (PostgreSQL Enhanced Types Testing)
-- ====================
CREATE TABLE IF NOT EXISTS enhanced_types (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    -- JSON types
    json_col JSON,
    jsonb_col JSONB,
    -- Array types
    int_array INTEGER[],
    text_array TEXT[],
    -- Enhanced datetime types
    timestamptz_col TIMESTAMPTZ,
    timetz_col TIMETZ,
    interval_col INTERVAL,
    -- Numeric types with precision
    numeric_col NUMERIC(10,2),
    -- Binary and network types
    bytea_col BYTEA,
    inet_col INET,
    cidr_col CIDR,
    macaddr_col MACADDR,
    -- XML type
    xml_col XML,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert enhanced types sample data
INSERT INTO enhanced_types (
    id, name, json_col, jsonb_col, int_array, text_array,
    timestamptz_col, timetz_col, interval_col, numeric_col,
    bytea_col, inet_col, cidr_col, macaddr_col, xml_col
) VALUES
(1, 'Test Record 1', 
 '{"name": "John", "age": 30, "city": "New York"}',
 '{"score": 95, "tags": ["developer", "java"], "active": true}',
 '{1, 2, 3, 4, 5}',
 '{"apple", "banana", "cherry"}',
 '2023-01-15 10:30:00+00',
 '14:30:00+00',
 '2 days 3 hours',
 1234.56,
 '\x48656c6c6f',
 '192.168.1.1',
 '192.168.0.0/24',
 '08:00:27:00:00:00',
 '<person><n>John</n><age>30</age></person>'
),
(2, 'Test Record 2',
 '{"product": "laptop", "price": 1500, "specs": {"ram": "16GB", "cpu": "Intel i7"}}',
 '{"user_id": 123, "preferences": {"theme": "dark", "notifications": false}}',
 '{10, 20, 30}',
 '{"postgresql", "graphql", "java"}',
 '2023-02-20 15:45:00+00',
 '09:15:00+00',
 '1 week 2 days',
 2500.75,
 '\x576f726c64',
 '10.0.0.1',
 '10.0.0.0/16',
 '00:1B:44:11:3A:B7',
 '<product><n>Laptop</n><price>1500</price></product>'
),
(3, 'Test Record 3',
 '{"company": "TechCorp", "employees": 500, "founded": 2010}',
 '{"settings": {"auto_save": true, "theme": "light"}, "version": "2.1.0"}',
 '{100, 200, 300, 400}',
 '{"spring", "boot", "graphql"}',
 '2023-03-10 08:00:00+00',
 '16:45:00+00',
 '3 months 2 weeks',
 9999.99,
 '\x4578616d706c65',
 '172.16.254.1',
 '172.16.0.0/12',
 '00:50:56:C0:00:01',
 '<company><name>TechCorp</name><industry>Software</industry></company>'
);

-- ====================
-- SAMPLE VIEWS (Testing View Support)
-- ====================

-- Create a view showing active customers
CREATE OR REPLACE VIEW active_customers AS
SELECT 
    customer_id,
    first_name,
    last_name,
    email,
    create_date
FROM customer 
WHERE active = true;

-- Create a materialized view for enhanced types summary
CREATE MATERIALIZED VIEW enhanced_types_summary AS
SELECT 
    id,
    name,
    json_col->>'name' as json_name,
    array_length(int_array, 1) as array_size,
    timestamptz_col::date as created_date
FROM enhanced_types;

-- ====================
-- SAMPLE RELATIONSHIPS TABLE
-- ====================
CREATE TABLE IF NOT EXISTS orders (
    order_id SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES customer(customer_id),
    order_date DATE NOT NULL DEFAULT CURRENT_DATE,
    total_amount NUMERIC(10,2) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending'
);

-- Insert sample orders
INSERT INTO orders (order_id, customer_id, order_date, total_amount, status) VALUES
(1, 1, '2023-01-15', 299.99, 'completed'),
(2, 1, '2023-02-01', 149.50, 'completed'),
(3, 2, '2023-01-20', 89.99, 'pending'),
(4, 3, '2023-01-25', 199.99, 'shipped'),
(5, 5, '2023-02-10', 349.99, 'completed');

-- ====================
-- UPDATE SEQUENCES (important for Docker init)
-- ====================
SELECT setval('customer_customer_id_seq', 12, true);
SELECT setval('enhanced_types_id_seq', 3, true);
SELECT setval('orders_order_id_seq', 5, true);

-- ====================
-- CREATE INDEXES (for better performance)
-- ====================
CREATE INDEX IF NOT EXISTS idx_customer_email ON customer(email);
CREATE INDEX IF NOT EXISTS idx_customer_active ON customer(active);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_enhanced_types_jsonb ON enhanced_types USING GIN(jsonb_col);

-- ====================
-- DATABASE STATISTICS
-- ====================
ANALYZE customer;
ANALYZE enhanced_types;
ANALYZE orders;

-- Print initialization summary
DO $$
BEGIN
    RAISE NOTICE 'Excalibase E2E Database Initialized Successfully!';
    RAISE NOTICE 'Tables created: customer (% rows), enhanced_types (% rows), orders (% rows)', 
        (SELECT count(*) FROM customer),
        (SELECT count(*) FROM enhanced_types), 
        (SELECT count(*) FROM orders);
    RAISE NOTICE 'Views created: active_customers, enhanced_types_summary';
    RAISE NOTICE 'Ready for GraphQL API testing on port 10001';
END $$; 