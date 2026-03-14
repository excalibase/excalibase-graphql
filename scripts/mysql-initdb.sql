-- Excalibase GraphQL MySQL Initialization Script
-- Sample tables and data for e2e and benchmark testing

-- ====================
-- TABLES
-- ====================

CREATE TABLE IF NOT EXISTS customer (
    customer_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name  VARCHAR(45)  NOT NULL,
    last_name   VARCHAR(45)  NOT NULL,
    email       VARCHAR(100),
    active      TINYINT(1)   DEFAULT 1,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS orders (
    order_id    BIGINT        AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT        NOT NULL,
    total       DECIMAL(10,2) NOT NULL,
    status      VARCHAR(20)   DEFAULT 'pending',
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customer(customer_id)
);

CREATE TABLE IF NOT EXISTS product (
    product_id  BIGINT        AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    price       DECIMAL(10,2) NOT NULL,
    stock       INT           DEFAULT 0,
    active      TINYINT(1)    DEFAULT 1
);

-- ENUM column type
CREATE TABLE IF NOT EXISTS task (
    task_id     BIGINT        AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(200)  NOT NULL,
    status      ENUM('todo', 'in_progress', 'done', 'cancelled') DEFAULT 'todo',
    priority    ENUM('low', 'medium', 'high', 'critical')        DEFAULT 'medium',
    customer_id BIGINT,
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_customer FOREIGN KEY (customer_id) REFERENCES customer(customer_id)
);

-- JSON column type
CREATE TABLE IF NOT EXISTS product_detail (
    detail_id   BIGINT        AUTO_INCREMENT PRIMARY KEY,
    product_id  BIGINT        NOT NULL,
    attributes  JSON,
    metadata    JSON,
    tags        JSON,
    CONSTRAINT fk_detail_product FOREIGN KEY (product_id) REFERENCES product(product_id)
);

-- ====================
-- VIEWS
-- ====================

CREATE OR REPLACE VIEW active_customers AS
SELECT customer_id, first_name, last_name, email, created_at
FROM customer
WHERE active = 1;

CREATE OR REPLACE VIEW orders_summary AS
SELECT
    c.customer_id,
    c.first_name,
    c.last_name,
    COUNT(o.order_id)  AS order_count,
    SUM(o.total)       AS total_spent
FROM customer c
LEFT JOIN orders o ON c.customer_id = o.customer_id
GROUP BY c.customer_id, c.first_name, c.last_name;

CREATE OR REPLACE VIEW high_value_orders AS
SELECT o.order_id, o.total, o.status, o.created_at,
       c.first_name, c.last_name
FROM orders o
JOIN customer c ON o.customer_id = c.customer_id
WHERE o.total > 50.00;

-- ====================
-- SAMPLE DATA
-- ====================

INSERT INTO customer (first_name, last_name, email, active) VALUES
('MARY',    'SMITH',     'mary.smith@example.com',     1),
('PATRICIA','JOHNSON',   'patricia.j@example.com',     1),
('JOHN',    'WILLIAMS',  'john.w@example.com',         1),
('MICHAEL', 'BROWN',     'michael.b@example.com',      1),
('LINDA',   'JONES',     'linda.j@example.com',        1),
('WILLIAM', 'GARCIA',    'william.g@example.com',      1),
('BARBARA', 'MILLER',    'barbara.m@example.com',      0),
('JAMES',   'DAVIS',     'james.d@example.com',        1),
('ELIZABETH','RODRIGUEZ','elizabeth.r@example.com',    1),
('ROBERT',  'MARTINEZ',  'robert.m@example.com',       1),
('JENNIFER','HERNANDEZ', 'jennifer.h@example.com',     1),
('DAVID',   'LOPEZ',     'david.l@example.com',        1),
('MARIA',   'GONZALEZ',  'maria.g@example.com',        0),
('CHARLES', 'WILSON',    'charles.w@example.com',      1),
('SUSAN',   'ANDERSON',  'susan.a@example.com',        1),
('JOSEPH',  'THOMAS',    'joseph.t@example.com',       1),
('JESSICA', 'TAYLOR',    'jessica.t@example.com',      1),
('THOMAS',  'MOORE',     'thomas.m@example.com',       1),
('SARAH',   'JACKSON',   'sarah.j@example.com',        1),
('CHRISTOPHER','MARTIN', 'christopher.m@example.com',  1);

INSERT INTO orders (customer_id, total, status) VALUES
(1,  9.99,   'delivered'),
(1,  24.99,  'processing'),
(2,  14.99,  'shipped'),
(3,  29.99,  'pending'),
(3,  4.99,   'delivered'),
(4,  39.99,  'delivered'),
(5,  19.99,  'cancelled'),
(6,  49.99,  'processing'),
(7,  7.99,   'delivered'),
(8,  59.99,  'pending'),
(9,  11.99,  'shipped'),
(10, 99.99,  'delivered'),
(11, 34.99,  'processing'),
(12, 44.99,  'delivered'),
(13, 54.99,  'pending'),
(14, 64.99,  'shipped'),
(15, 74.99,  'delivered'),
(16, 84.99,  'pending'),
(17, 94.99,  'delivered'),
(18, 104.99, 'processing');

INSERT INTO product (name, price, stock, active) VALUES
('Widget A',     9.99,  100, 1),
('Widget B',    19.99,   50, 1),
('Gadget Pro',  49.99,   25, 1),
('Gadget Lite', 29.99,   75, 1),
('Super Tool',  99.99,   10, 1),
('Mini Tool',   14.99,  200, 1),
('Premium Kit', 149.99,   5, 1),
('Basic Kit',   24.99,  150, 1),
('Deluxe Pack', 199.99,   3, 0),
('Starter Pack', 4.99,  500, 1);

INSERT INTO task (title, status, priority, customer_id) VALUES
('Setup account',     'done',        'high',     1),
('Review order',      'in_progress', 'medium',   2),
('Send invoice',      'todo',        'low',       3),
('Process refund',    'in_progress', 'critical', 4),
('Update profile',    'done',        'low',       1),
('Schedule meeting',  'todo',        'medium',   5),
('Fix billing issue', 'in_progress', 'high',     6),
('Close ticket',      'cancelled',   'low',       7),
('Follow up',         'todo',        'medium',   8),
('Send reminder',     'todo',        'low',       9);

INSERT INTO product_detail (product_id, attributes, metadata, tags) VALUES
(1, '{"color": "blue", "weight": 0.5, "dimensions": {"w": 10, "h": 5}}',
    '{"sku": "WGT-A-001", "warehouse": "US-EAST"}',
    '["sale", "popular"]'),
(2, '{"color": "red", "weight": 0.8, "waterproof": true}',
    '{"sku": "WGT-B-002", "warehouse": "US-WEST"}',
    '["new", "featured"]'),
(3, '{"color": "black", "weight": 1.2, "battery": "rechargeable", "voltage": 5}',
    '{"sku": "GAD-PRO-003", "warranty_years": 2}',
    '["premium", "bestseller"]'),
(4, '{"color": "white", "weight": 0.9}',
    '{"sku": "GAD-LT-004", "warehouse": "EU-WEST"}',
    '["budget", "popular"]'),
(5, '{"material": "steel", "weight": 2.5, "max_load": 100}',
    '{"sku": "SUP-TOOL-005", "warranty_years": 5}',
    '["industrial", "heavy-duty"]');
