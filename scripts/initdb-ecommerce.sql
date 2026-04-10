CREATE SCHEMA shopify;

-- Enums
CREATE TYPE shopify.product_status AS ENUM ('draft', 'active', 'archived');
CREATE TYPE shopify.order_status AS ENUM ('pending', 'paid', 'shipped', 'delivered', 'cancelled');
CREATE TYPE shopify.payment_method AS ENUM ('card', 'bank', 'wallet');
CREATE TYPE shopify.payment_status AS ENUM ('pending', 'completed', 'failed', 'refunded');

-- Categories (self-referential tree)
CREATE TABLE shopify.categories (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    parent_category_id INTEGER REFERENCES shopify.categories(id)
);

-- Products (soft delete via deleted_at)
CREATE TABLE shopify.products (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    slug TEXT UNIQUE NOT NULL,
    price NUMERIC(10,2) NOT NULL,
    category_id INTEGER REFERENCES shopify.categories(id),
    status shopify.product_status DEFAULT 'active',
    metadata JSONB DEFAULT '{}',
    deleted_at TIMESTAMPTZ
);

-- Product variants (SKU-level inventory)
CREATE TABLE shopify.product_variants (
    id SERIAL PRIMARY KEY,
    product_id INTEGER NOT NULL REFERENCES shopify.products(id),
    sku TEXT UNIQUE NOT NULL,
    color TEXT,
    size TEXT,
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    price_override NUMERIC(10,2)
);

-- Customers
CREATE TABLE shopify.customers (
    id SERIAL PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    phone TEXT,
    address JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Orders
CREATE TABLE shopify.orders (
    id SERIAL PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES shopify.customers(id),
    status shopify.order_status DEFAULT 'pending',
    total NUMERIC(10,2) NOT NULL DEFAULT 0,
    notes TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Order items (FK to order + variant)
CREATE TABLE shopify.order_items (
    id SERIAL PRIMARY KEY,
    order_id INTEGER NOT NULL REFERENCES shopify.orders(id),
    variant_id INTEGER NOT NULL REFERENCES shopify.product_variants(id),
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price NUMERIC(10,2) NOT NULL,
    line_total NUMERIC(10,2) GENERATED ALWAYS AS (quantity * unit_price) STORED
);

-- Payments
CREATE TABLE shopify.payments (
    id SERIAL PRIMARY KEY,
    order_id INTEGER NOT NULL REFERENCES shopify.orders(id),
    method shopify.payment_method NOT NULL,
    amount NUMERIC(10,2) NOT NULL,
    provider_tx_id TEXT,
    status shopify.payment_status DEFAULT 'pending',
    paid_at TIMESTAMPTZ
);

-- Reviews (2 FKs: product + customer)
CREATE TABLE shopify.reviews (
    id SERIAL PRIMARY KEY,
    product_id INTEGER NOT NULL REFERENCES shopify.products(id),
    customer_id INTEGER NOT NULL REFERENCES shopify.customers(id),
    rating INTEGER NOT NULL CHECK (rating >= 1 AND rating <= 5),
    title TEXT,
    body TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Wishlists (composite PK)
CREATE TABLE shopify.wishlists (
    customer_id INTEGER NOT NULL REFERENCES shopify.customers(id),
    product_id INTEGER NOT NULL REFERENCES shopify.products(id),
    added_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (customer_id, product_id)
);

-- Views
CREATE VIEW shopify.order_summary AS
    SELECT o.id AS order_id, c.name AS customer_name, o.status, o.total,
           COUNT(oi.id) AS item_count, o.created_at
    FROM shopify.orders o
    JOIN shopify.customers c ON c.id = o.customer_id
    LEFT JOIN shopify.order_items oi ON oi.order_id = o.id
    GROUP BY o.id, c.name, o.status, o.total, o.created_at;

CREATE VIEW shopify.product_catalog AS
    SELECT p.id, p.name, p.price, p.status, cat.name AS category_name,
           COALESCE(AVG(r.rating), 0) AS avg_rating,
           COALESCE(SUM(pv.stock_quantity), 0) AS total_stock
    FROM shopify.products p
    LEFT JOIN shopify.categories cat ON cat.id = p.category_id
    LEFT JOIN shopify.reviews r ON r.product_id = p.id
    LEFT JOIN shopify.product_variants pv ON pv.product_id = p.id
    WHERE p.deleted_at IS NULL
    GROUP BY p.id, p.name, p.price, p.status, cat.name;

-- Seed data
INSERT INTO shopify.categories (name, parent_category_id) VALUES
    ('Electronics', NULL),
    ('Clothing', NULL),
    ('Phones', 1),
    ('Laptops', 1),
    ('T-Shirts', 2);

INSERT INTO shopify.products (name, slug, price, category_id, status, metadata) VALUES
    ('iPhone 15', 'iphone-15', 999.00, 3, 'active', '{"brand":"Apple","warranty":"1yr"}'),
    ('Galaxy S24', 'galaxy-s24', 899.00, 3, 'active', '{"brand":"Samsung"}'),
    ('MacBook Pro', 'macbook-pro', 2499.00, 4, 'active', '{"brand":"Apple","ram":"16GB"}'),
    ('ThinkPad X1', 'thinkpad-x1', 1899.00, 4, 'active', '{"brand":"Lenovo"}'),
    ('Classic Tee', 'classic-tee', 29.99, 5, 'active', '{}'),
    ('V-Neck Tee', 'vneck-tee', 34.99, 5, 'active', '{}'),
    ('Old Phone', 'old-phone', 199.00, 3, 'archived', '{}'),
    ('Deleted Item', 'deleted-item', 9.99, NULL, 'draft', '{}');

UPDATE shopify.products SET deleted_at = now() WHERE slug = 'deleted-item';

INSERT INTO shopify.product_variants (product_id, sku, color, size, stock_quantity, price_override) VALUES
    (1, 'IP15-BLK-128', 'Black', '128GB', 50, NULL),
    (1, 'IP15-WHT-256', 'White', '256GB', 30, 1099.00),
    (2, 'GS24-BLK-128', 'Black', '128GB', 45, NULL),
    (2, 'GS24-BLU-256', 'Blue', '256GB', 20, 999.00),
    (3, 'MBP-SLV-14', 'Silver', '14"', 15, NULL),
    (3, 'MBP-GRY-16', 'Space Gray', '16"', 10, 2999.00),
    (4, 'TX1-BLK-14', 'Black', '14"', 25, NULL),
    (5, 'CT-WHT-M', 'White', 'M', 100, NULL),
    (5, 'CT-WHT-L', 'White', 'L', 80, NULL),
    (5, 'CT-BLK-M', 'Black', 'M', 90, NULL),
    (6, 'VN-GRY-M', 'Gray', 'M', 60, NULL),
    (6, 'VN-GRY-L', 'Gray', 'L', 40, NULL);

INSERT INTO shopify.customers (email, name, phone, address) VALUES
    ('alice@shop.com', 'Alice Johnson', '+1-555-0101', '{"street":"123 Main St","city":"New York","zip":"10001"}'),
    ('bob@shop.com', 'Bob Smith', '+1-555-0102', '{"street":"456 Oak Ave","city":"Chicago","zip":"60601"}'),
    ('carol@shop.com', 'Carol Davis', '+1-555-0103', '{"street":"789 Pine Rd","city":"Austin","zip":"78701"}'),
    ('dave@shop.com', 'Dave Wilson', '+1-555-0104', NULL),
    ('eve@shop.com', 'Eve Brown', '+1-555-0105', '{"street":"321 Elm St","city":"Seattle","zip":"98101"}');

INSERT INTO shopify.orders (customer_id, status, total) VALUES
    (1, 'delivered', 999.00),
    (1, 'shipped', 2499.00),
    (2, 'paid', 1099.00),
    (2, 'pending', 29.99),
    (3, 'delivered', 1898.00),
    (3, 'cancelled', 34.99),
    (4, 'paid', 999.00),
    (4, 'shipped', 64.98),
    (5, 'delivered', 899.00),
    (5, 'pending', 2999.00);

INSERT INTO shopify.order_items (order_id, variant_id, quantity, unit_price) VALUES
    (1, 1, 1, 999.00),
    (2, 5, 1, 2499.00),
    (3, 2, 1, 1099.00),
    (4, 8, 1, 29.99),
    (5, 1, 1, 999.00),
    (5, 3, 1, 899.00),
    (6, 11, 1, 34.99),
    (7, 4, 1, 999.00),
    (8, 8, 1, 29.99),
    (8, 10, 1, 29.99),
    (9, 3, 1, 899.00),
    (10, 6, 1, 2999.00);

INSERT INTO shopify.payments (order_id, method, amount, provider_tx_id, status, paid_at) VALUES
    (1, 'card', 999.00, 'txn_001', 'completed', now() - interval '30 days'),
    (2, 'card', 2499.00, 'txn_002', 'completed', now() - interval '7 days'),
    (3, 'bank', 1099.00, 'txn_003', 'completed', now() - interval '3 days'),
    (4, 'wallet', 29.99, NULL, 'pending', NULL),
    (5, 'card', 1898.00, 'txn_005', 'completed', now() - interval '14 days'),
    (6, 'card', 34.99, 'txn_006', 'refunded', now() - interval '10 days'),
    (7, 'card', 999.00, 'txn_007', 'completed', now() - interval '2 days'),
    (8, 'wallet', 64.98, 'txn_008', 'completed', now() - interval '1 day'),
    (9, 'bank', 899.00, 'txn_009', 'completed', now() - interval '5 days'),
    (10, 'card', 2999.00, NULL, 'pending', NULL);

INSERT INTO shopify.reviews (product_id, customer_id, rating, title, body) VALUES
    (1, 1, 5, 'Amazing phone', 'Best phone I ever had'),
    (1, 2, 4, 'Great but expensive', 'Good quality, pricey'),
    (1, 3, 5, 'Love it', 'Perfect for daily use'),
    (2, 2, 4, 'Solid choice', 'Great camera'),
    (3, 1, 5, 'Developer dream', 'Fast and reliable'),
    (3, 5, 3, 'Good but heavy', 'Performance is great but its heavy'),
    (5, 4, 4, 'Comfy tee', 'Soft fabric, fits well'),
    (5, 3, 5, 'Best basic tee', 'Buy 10 of these');

INSERT INTO shopify.wishlists (customer_id, product_id) VALUES
    (1, 2), (1, 4), (2, 1), (2, 3), (3, 6), (5, 1);
