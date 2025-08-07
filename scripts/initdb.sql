-- Excalibase GraphQL Database Initialization Script
-- This script creates comprehensive sample tables and data for both demo and testing
-- Includes enhanced PostgreSQL types, relationships, and views

-- ====================
-- SCHEMA SETUP
-- ====================
CREATE SCHEMA IF NOT EXISTS hana;
SET search_path TO hana;

-- ====================
-- CUSTOM TYPES (ENUM AND COMPOSITE)
-- ====================

-- Custom enum types for testing
CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled');
CREATE TYPE user_role AS ENUM ('admin', 'moderator', 'user', 'guest');
CREATE TYPE priority_level AS ENUM ('low', 'medium', 'high', 'critical');

-- Custom composite object types for testing
CREATE TYPE address AS (
    street VARCHAR(100),
    city VARCHAR(50),
    state VARCHAR(50),
    postal_code VARCHAR(20),
    country VARCHAR(50)
);

CREATE TYPE contact_info AS (
    email VARCHAR(100),
    phone VARCHAR(20),
    website VARCHAR(100)
);

CREATE TYPE product_dimensions AS (
    length DECIMAL(10,2),
    width DECIMAL(10,2),
    height DECIMAL(10,2),
    weight DECIMAL(10,2),
    units VARCHAR(10)
);

-- Domain types for testing
CREATE DOMAIN email_domain AS VARCHAR(100) 
    CHECK (VALUE ~ '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$');

CREATE DOMAIN positive_integer AS INTEGER 
    CHECK (VALUE > 0);

CREATE DOMAIN price_domain AS DECIMAL(10,2) 
    CHECK (VALUE >= 0.00);

CREATE DOMAIN username_domain AS VARCHAR(50) 
    CHECK (LENGTH(VALUE) >= 3 AND VALUE ~ '^[a-zA-Z0-9_-]+$');

CREATE DOMAIN text_array_domain AS TEXT[];

CREATE DOMAIN rating_domain AS INTEGER 
    CHECK (VALUE >= 1 AND VALUE <= 5);

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
    role user_role DEFAULT 'user',
    shipping_address address,
    contact contact_info,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sample posts table
CREATE TABLE IF NOT EXISTS posts (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    author_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    priority priority_level DEFAULT 'medium',
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

-- Sample tasks table for testing
CREATE TABLE IF NOT EXISTS tasks (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    priority priority_level DEFAULT 'medium',
    assigned_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    completed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert demo data with custom types
INSERT INTO users (username, email, first_name, last_name, role, shipping_address, contact) VALUES
    ('john_doe', 'john@example.com', 'John', 'Doe', 'user', 
     ROW('123 Main St', 'New York', 'NY', '10001', 'USA')::address,
     ROW('john@example.com', '+1-555-0123', 'https://johndoe.com')::contact_info),
    ('jane_smith', 'jane@example.com', 'Jane', 'Smith', 'moderator',
     ROW('456 Oak Ave', 'Los Angeles', 'CA', '90210', 'USA')::address,
     ROW('jane@example.com', '+1-555-0456', 'https://janesmith.blog')::contact_info),
    ('bob_wilson', 'bob@example.com', 'Bob', 'Wilson', 'admin',
     ROW('789 Pine St', 'Seattle', 'WA', '98101', 'USA')::address,
     ROW('bob@example.com', '+1-555-0789', null)::contact_info)
ON CONFLICT (username) DO NOTHING;

INSERT INTO posts (title, content, author_id, priority, published) VALUES
    ('Introduction to GraphQL', 'GraphQL is a query language for APIs that gives clients the power to ask for exactly what they need...', 1, 'high', true),
    ('Getting Started with Docker', 'Docker is a containerization platform that makes it easy to package and deploy applications...', 2, 'medium', true),
    ('Spring Boot Best Practices', 'Here are some best practices for Spring Boot development: use profiles, externalize configuration...', 1, 'low', false),
    ('Database Design Patterns', 'When designing databases, consider these patterns: normalization, indexing strategies, foreign keys...', 3, 'critical', true)
ON CONFLICT DO NOTHING;

INSERT INTO comments (content, post_id, author_id) VALUES
    ('Great introduction! This helped me understand GraphQL better.', 1, 2),
    ('Very helpful, thanks! Looking forward to more GraphQL content.', 1, 3),
    ('Looking forward to more posts like this. Docker has been a game changer.', 2, 1),
    ('Could you elaborate on the security aspects of these patterns?', 4, 2)
ON CONFLICT DO NOTHING;

INSERT INTO tasks (title, description, priority, assigned_user_id, completed) VALUES
    ('Setup GraphQL API', 'Configure and deploy the GraphQL API for the project', 'high', 1, true),
    ('Write unit tests', 'Add comprehensive unit tests for all modules', 'medium', 2, false),
    ('Documentation update', 'Update API documentation with new features', 'low', 1, false),
    ('Performance optimization', 'Optimize database queries and API response times', 'critical', 3, false)
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
    status order_status DEFAULT 'pending',
    shipping_address address,
    billing_address address
);

-- Insert sample orders with custom types
INSERT INTO orders (order_id, customer_id, order_date, total_amount, status, shipping_address, billing_address) VALUES
(1, 1, '2023-01-15', 299.99, 'delivered',
 ROW('123 Delivery St', 'New York', 'NY', '10001', 'USA')::address,
 ROW('123 Billing Ave', 'New York', 'NY', '10001', 'USA')::address),
(2, 1, '2023-02-01', 149.50, 'shipped',
 ROW('123 Delivery St', 'New York', 'NY', '10001', 'USA')::address,
 ROW('123 Billing Ave', 'New York', 'NY', '10001', 'USA')::address),
(3, 2, '2023-01-20', 89.99, 'processing',
 ROW('456 Ship St', 'Los Angeles', 'CA', '90210', 'USA')::address,
 ROW('456 Bill Ave', 'Los Angeles', 'CA', '90210', 'USA')::address),
(4, 3, '2023-01-25', 199.99, 'pending',
 ROW('789 Receive Rd', 'Seattle', 'WA', '98101', 'USA')::address,
 ROW('789 Pay St', 'Seattle', 'WA', '98101', 'USA')::address),
(5, 5, '2023-02-10', 349.99, 'cancelled',
 ROW('555 Cancel St', 'Chicago', 'IL', '60601', 'USA')::address,
 ROW('555 Refund Ave', 'Chicago', 'IL', '60601', 'USA')::address)
ON CONFLICT (order_id) DO NOTHING;

-- Products table demonstrating comprehensive custom type usage
CREATE TABLE IF NOT EXISTS products (
    product_id SERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    price NUMERIC(10,2) NOT NULL,
    category VARCHAR(50),
    priority priority_level DEFAULT 'medium',
    dimensions product_dimensions,
    supplier_contact contact_info,
    origin_address address,
    status order_status DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert sample products with all custom types
INSERT INTO products (product_id, name, description, price, category, priority, dimensions, supplier_contact, origin_address, status) VALUES
(1, 'Premium Laptop', 'High-performance laptop for developers', 1299.99, 'Electronics', 'high',
 ROW(35.56, 24.13, 2.03, 1.8, 'cm')::product_dimensions,
 ROW('supplier@techcorp.com', '+1-800-TECH', 'https://techcorp.com')::contact_info,
 ROW('100 Tech Park', 'Cupertino', 'CA', '95014', 'USA')::address,
 'delivered'),
(2, 'Ergonomic Mouse', 'Wireless ergonomic mouse with precision tracking', 79.99, 'Electronics', 'medium',
 ROW(12.7, 7.6, 4.1, 0.15, 'cm')::product_dimensions,
 ROW('orders@peripherals.com', '+1-555-MOUSE', 'https://peripherals.com')::contact_info,
 ROW('200 Device Blvd', 'Austin', 'TX', '73301', 'USA')::address,
 'shipped'),
(3, 'Mechanical Keyboard', 'RGB backlit mechanical keyboard', 199.99, 'Electronics', 'low',
 ROW(43.18, 13.97, 4.32, 1.2, 'cm')::product_dimensions,
 ROW('support@keyboards.com', '+1-555-KEYS', null)::contact_info,
 ROW('300 Key Street', 'Portland', 'OR', '97201', 'USA')::address,
 'processing'),
(4, 'Desk Organizer', 'Bamboo desk organizer with multiple compartments', 45.50, 'Office', 'critical',
 ROW(30.0, 20.0, 8.0, 0.8, 'cm')::product_dimensions,
 ROW('sales@bamboo.com', '+1-555-WOOD', 'https://bamboo.com')::contact_info,
 ROW('400 Green Way', 'Portland', 'OR', '97202', 'USA')::address,
 'pending')
ON CONFLICT (product_id) DO NOTHING;

-- Custom types test table for comprehensive enum and composite type testing
CREATE TABLE IF NOT EXISTS custom_types_test (
    id SERIAL PRIMARY KEY,
    -- All enum types
    status order_status DEFAULT 'pending',
    role user_role DEFAULT 'user', 
    priority priority_level DEFAULT 'medium',
    -- All composite types
    main_address address,
    contact_details contact_info,
    product_specs product_dimensions,
    -- Mixed usage
    backup_addresses address[],  -- Array of composite type
    allowed_roles user_role[],   -- Array of enum type
    -- Additional fields
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Domain types test table for comprehensive domain type testing
CREATE TABLE IF NOT EXISTS domain_types_test (
    id SERIAL PRIMARY KEY,
    -- Domain types usage
    email email_domain NOT NULL,
    quantity positive_integer DEFAULT 1,
    price price_domain DEFAULT 0.00,
    username username_domain UNIQUE NOT NULL,
    tags text_array_domain,
    rating rating_domain,
    -- Mixed with regular types
    description TEXT,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert comprehensive test data
INSERT INTO custom_types_test (
    id, status, role, priority, main_address, contact_details, product_specs,
    backup_addresses, allowed_roles, name
) VALUES
(1, 'pending', 'admin', 'critical',
 ROW('123 Test St', 'Test City', 'TS', '12345', 'TestLand')::address,
 ROW('test@example.com', '+1-555-TEST', 'https://test.com')::contact_info,
 ROW(10.5, 20.3, 5.1, 2.5, 'cm')::product_dimensions,
 ARRAY[
     ROW('456 Backup Ave', 'Backup City', 'BC', '67890', 'BackupLand')::address,
     ROW('789 Fallback Rd', 'Fallback Town', 'FT', '54321', 'FallbackNation')::address
 ],
 ARRAY['admin', 'moderator']::user_role[],
 'Comprehensive Test Record 1'),
(2, 'delivered', 'moderator', 'high',
 ROW('999 Final St', 'Final City', 'FC', '99999', 'FinalCountry')::address,
 ROW('final@test.com', '+1-999-FINAL', null)::contact_info,
 ROW(50.0, 30.0, 15.0, 5.0, 'inches')::product_dimensions,
 ARRAY[
     ROW('111 Alt St', 'Alt City', 'AC', '11111', 'AltWorld')::address
 ],
 ARRAY['moderator', 'user', 'guest']::user_role[],
 'Advanced Test Record 2'),
(3, 'shipped', 'user', 'low',
 ROW('777 User Blvd', 'User Town', 'UT', '77777', 'UserNation')::address,
 ROW('user@domain.com', '+1-777-USER', 'https://usersite.org')::contact_info,
 ROW(100.0, 200.0, 300.0, 50.0, 'mm')::product_dimensions,
 ARRAY[]::address[],  -- Empty array
 ARRAY['user']::user_role[],
 'Basic Test Record 3')
ON CONFLICT (id) DO NOTHING;

-- Insert domain types test data
INSERT INTO domain_types_test (
    id, email, quantity, price, username, tags, rating, description, is_active
) VALUES
(1, 'john.doe@example.com', 5, 29.99, 'john_doe', 
 ARRAY['tech', 'programming', 'java']::text_array_domain, 
 5, 'Expert Java developer', true),
(2, 'jane.smith@company.org', 10, 45.50, 'jane_smith',
 ARRAY['design', 'ui', 'ux']::text_array_domain,
 4, 'Senior UX designer', true),
(3, 'bob.wilson@startup.io', 1, 15.00, 'bob_wilson',
 ARRAY['startup', 'entrepreneur']::text_array_domain,
 3, 'Startup founder', false),
(4, 'alice.brown@university.edu', 25, 199.99, 'alice_brown',
 ARRAY['research', 'ai', 'machine-learning']::text_array_domain,
 5, 'AI researcher and professor', true)
ON CONFLICT (id) DO NOTHING;

-- Table with composite primary key for testing
CREATE TABLE IF NOT EXISTS order_items (
    order_id INTEGER NOT NULL REFERENCES orders(order_id),
    product_id INTEGER NOT NULL REFERENCES products(product_id),
    quantity positive_integer NOT NULL,
    price price_domain NOT NULL,
    PRIMARY KEY (order_id, product_id)
);

-- Insert sample data for order_items
INSERT INTO order_items (order_id, product_id, quantity, price) VALUES
(1, 1, 2, 2599.98),
(1, 2, 1, 79.99),
(2, 3, 3, 599.97)
ON CONFLICT DO NOTHING;

-- Parent table with composite primary key
CREATE TABLE IF NOT EXISTS parent_table (
    parent_id1 INTEGER NOT NULL,
    parent_id2 INTEGER NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (parent_id1, parent_id2)
);

-- Child table with composite foreign key referencing parent
CREATE TABLE IF NOT EXISTS child_table (
    child_id INTEGER PRIMARY KEY,
    parent_id1 INTEGER NOT NULL,
    parent_id2 INTEGER NOT NULL,
    description TEXT,
    FOREIGN KEY (parent_id1, parent_id2) REFERENCES parent_table(parent_id1, parent_id2)
);

-- Insert sample data for parent_table
INSERT INTO parent_table (parent_id1, parent_id2, name) VALUES
(1, 1, 'Parent 1-1'),
(1, 2, 'Parent 1-2'),
(2, 1, 'Parent 2-1')
ON CONFLICT DO NOTHING;

-- Insert sample data for child_table
INSERT INTO child_table (child_id, parent_id1, parent_id2, description) VALUES
(1, 1, 1, 'Child of 1-1'),
(2, 1, 2, 'Child of 1-2'),
(3, 2, 1, 'Child of 2-1')
ON CONFLICT DO NOTHING;

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
SELECT setval('tasks_id_seq', 4, true);
SELECT setval('customer_customer_id_seq', 12, true);
SELECT setval('enhanced_types_id_seq', 3, true);
SELECT setval('orders_order_id_seq', 5, true);
SELECT setval('products_product_id_seq', 4, true);
SELECT setval('custom_types_test_id_seq', 3, true);
-- Note: child_table sequence handled automatically by PostgreSQL

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
CREATE INDEX IF NOT EXISTS idx_tasks_assigned_user_id ON tasks(assigned_user_id);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- Test tables indexes
CREATE INDEX IF NOT EXISTS idx_customer_email ON customer(email);
CREATE INDEX IF NOT EXISTS idx_customer_active ON customer(active);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_enhanced_types_jsonb ON enhanced_types USING GIN(jsonb_col);
CREATE INDEX IF NOT EXISTS idx_products_name ON products(name);
CREATE INDEX IF NOT EXISTS idx_custom_types_test_name ON custom_types_test(name);

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
ANALYZE tasks;
ANALYZE customer;
ANALYZE enhanced_types;
ANALYZE orders;
ANALYZE products;
ANALYZE custom_types_test;
ANALYZE order_items;
ANALYZE parent_table;
ANALYZE child_table;

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
    RAISE NOTICE '  - tasks: % rows', (SELECT count(*) FROM tasks);
    RAISE NOTICE '';
    RAISE NOTICE 'TEST TABLES:';
    RAISE NOTICE '  - customer: % rows', (SELECT count(*) FROM customer);
    RAISE NOTICE '  - enhanced_types: % rows', (SELECT count(*) FROM enhanced_types);
    RAISE NOTICE '  - orders: % rows', (SELECT count(*) FROM orders);
    RAISE NOTICE '  - products: % rows', (SELECT count(*) FROM products);
    RAISE NOTICE '  - custom_types_test: % rows', (SELECT count(*) FROM custom_types_test);
    RAISE NOTICE '  - order_items: % rows', (SELECT count(*) FROM order_items);
    RAISE NOTICE '  - parent_table: % rows', (SELECT count(*) FROM parent_table);
    RAISE NOTICE '  - child_table: % rows', (SELECT count(*) FROM child_table);
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