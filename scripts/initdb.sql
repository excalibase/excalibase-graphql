-- Excalibase GraphQL Database Initialization Script
-- This script creates comprehensive sample tables and data for both demo and testing
-- Includes enhanced PostgreSQL types, relationships, and views

-- ====================
-- SCHEMA SETUP
-- ====================
CREATE SCHEMA IF NOT EXISTS hana;
SET search_path TO hana;

-- ====================
-- DEMO TABLES (Blog-style for demos and documentation)
-- ====================

-- Sample users table
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sample posts table
CREATE TABLE IF NOT EXISTS posts (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    author_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    published BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sample comments table
CREATE TABLE IF NOT EXISTS comments (
    id SERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    post_id INTEGER REFERENCES posts(id) ON DELETE CASCADE,
    author_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert demo data
INSERT INTO users (username, email, first_name, last_name) VALUES
    ('john_doe', 'john@example.com', 'John', 'Doe'),
    ('jane_smith', 'jane@example.com', 'Jane', 'Smith'),
    ('bob_wilson', 'bob@example.com', 'Bob', 'Wilson')
ON CONFLICT (username) DO NOTHING;

INSERT INTO posts (title, content, author_id, published) VALUES
    ('Introduction to GraphQL', 'GraphQL is a query language for APIs that gives clients the power to ask for exactly what they need...', 1, true),
    ('Getting Started with Docker', 'Docker is a containerization platform that makes it easy to package and deploy applications...', 2, true),
    ('Spring Boot Best Practices', 'Here are some best practices for Spring Boot development: use profiles, externalize configuration...', 1, false),
    ('Database Design Patterns', 'When designing databases, consider these patterns: normalization, indexing strategies, foreign keys...', 3, true)
ON CONFLICT DO NOTHING;

INSERT INTO comments (content, post_id, author_id) VALUES
    ('Great introduction! This helped me understand GraphQL better.', 1, 2),
    ('Very helpful, thanks! Looking forward to more GraphQL content.', 1, 3),
    ('Looking forward to more posts like this. Docker has been a game changer.', 2, 1),
    ('Could you elaborate on the security aspects of these patterns?', 4, 2)
ON CONFLICT DO NOTHING;

-- ====================
-- TEST TABLES (Comprehensive for E2E testing)
-- ====================

-- Customer table for basic testing
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
(12, 'JOHN', 'SMITH', 'john.smith@example.com', true, '2007-01-01', '2013-05-26 14:49:45')
ON CONFLICT (customer_id) DO NOTHING;

-- Enhanced types table for PostgreSQL advanced features testing
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
 '<person><name>John</name><age>30</age></person>'
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
 '<product><name>Laptop</name><price>1500</price></product>'
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
)
ON CONFLICT (id) DO NOTHING;

-- Orders table for relationship testing
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
(5, 5, '2023-02-10', 349.99, 'completed')
ON CONFLICT (order_id) DO NOTHING;

-- ====================
-- VIEWS AND MATERIALIZED VIEWS
-- ====================

-- View showing active customers
CREATE OR REPLACE VIEW active_customers AS
SELECT 
    customer_id,
    first_name,
    last_name,
    email,
    create_date
FROM customer 
WHERE active = true;

-- Materialized view for enhanced types summary
CREATE MATERIALIZED VIEW IF NOT EXISTS enhanced_types_summary AS
SELECT 
    id,
    name,
    json_col->>'name' as json_name,
    array_length(int_array, 1) as array_size,
    timestamptz_col::date as created_date
FROM enhanced_types;

-- View for blog posts with author info
CREATE OR REPLACE VIEW posts_with_authors AS
SELECT 
    p.id,
    p.title,
    p.content,
    p.published,
    p.created_at,
    u.username as author_username,
    u.first_name as author_first_name,
    u.last_name as author_last_name
FROM posts p
JOIN users u ON p.author_id = u.id;

-- ====================
-- SEQUENCES UPDATE
-- ====================
SELECT setval('users_id_seq', 3, true);
SELECT setval('posts_id_seq', 4, true);
SELECT setval('comments_id_seq', 4, true);
SELECT setval('customer_customer_id_seq', 12, true);
SELECT setval('enhanced_types_id_seq', 3, true);
SELECT setval('orders_order_id_seq', 5, true);

-- ====================
-- PERMISSIONS
-- ====================
-- Grant permissions to users conditionally (handles both production and test environments)
DO $$
BEGIN
    -- Grant permissions to main application user (hana001) if it exists
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hana001') THEN
        GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA hana TO hana001;
        GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA hana TO hana001;
        GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA hana TO hana001;
        RAISE NOTICE 'Granted permissions to hana001 (production user)';
    ELSE
        RAISE NOTICE 'Production user hana001 not found, skipping production permissions';
    END IF;
    
    -- Grant permissions to test user (excalibase_user) if it exists
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'excalibase_user') THEN
        GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA hana TO excalibase_user;
        GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA hana TO excalibase_user;
        GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA hana TO excalibase_user;
        RAISE NOTICE 'Granted permissions to excalibase_user (test user)';
    ELSE
        RAISE NOTICE 'Test user excalibase_user not found, skipping test permissions';
    END IF;
END $$;

-- ====================
-- INDEXES FOR PERFORMANCE
-- ====================
-- Demo tables indexes
CREATE INDEX IF NOT EXISTS idx_posts_author_id ON posts(author_id);
CREATE INDEX IF NOT EXISTS idx_comments_post_id ON comments(post_id);
CREATE INDEX IF NOT EXISTS idx_comments_author_id ON comments(author_id);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- Test tables indexes
CREATE INDEX IF NOT EXISTS idx_customer_email ON customer(email);
CREATE INDEX IF NOT EXISTS idx_customer_active ON customer(active);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_enhanced_types_jsonb ON enhanced_types USING GIN(jsonb_col);

-- ====================
-- REFRESH MATERIALIZED VIEWS
-- ====================
REFRESH MATERIALIZED VIEW enhanced_types_summary;

-- ====================
-- ANALYZE TABLES FOR QUERY OPTIMIZATION
-- ====================
ANALYZE users;
ANALYZE posts;
ANALYZE comments;
ANALYZE customer;
ANALYZE enhanced_types;
ANALYZE orders;

-- ====================
-- INITIALIZATION SUMMARY
-- ====================
DO $$
BEGIN
    RAISE NOTICE '=================================================';
    RAISE NOTICE 'Excalibase GraphQL Database Initialized Successfully!';
    RAISE NOTICE '=================================================';
    RAISE NOTICE 'DEMO TABLES:';
    RAISE NOTICE '  - users: % rows', (SELECT count(*) FROM users);
    RAISE NOTICE '  - posts: % rows', (SELECT count(*) FROM posts);
    RAISE NOTICE '  - comments: % rows', (SELECT count(*) FROM comments);
    RAISE NOTICE '';
    RAISE NOTICE 'TEST TABLES:';
    RAISE NOTICE '  - customer: % rows', (SELECT count(*) FROM customer);
    RAISE NOTICE '  - enhanced_types: % rows', (SELECT count(*) FROM enhanced_types);
    RAISE NOTICE '  - orders: % rows', (SELECT count(*) FROM orders);
    RAISE NOTICE '';
    RAISE NOTICE 'VIEWS:';
    RAISE NOTICE '  - active_customers: % rows', (SELECT count(*) FROM active_customers);
    RAISE NOTICE '  - enhanced_types_summary: % rows', (SELECT count(*) FROM enhanced_types_summary);
    RAISE NOTICE '  - posts_with_authors: % rows', (SELECT count(*) FROM posts_with_authors);
    RAISE NOTICE '';
    RAISE NOTICE 'Schema: hana';
    RAISE NOTICE 'Ready for GraphQL API at port 10000';
    RAISE NOTICE '=================================================';
END $$; 