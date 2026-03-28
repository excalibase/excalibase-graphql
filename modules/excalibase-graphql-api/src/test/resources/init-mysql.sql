CREATE TABLE customer (
    customer_id INT AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    email VARCHAR(100),
    active BOOLEAN DEFAULT true
);

CREATE TABLE orders (
    order_id INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT,
    total_amount DECIMAL(10,2) NOT NULL,
    order_date DATE DEFAULT (CURRENT_DATE),
    FOREIGN KEY (customer_id) REFERENCES customer(customer_id)
);

CREATE TABLE order_items (
    order_id INT,
    product_id INT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    price DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (order_id, product_id),
    FOREIGN KEY (order_id) REFERENCES orders(order_id)
);

-- ENUM column type
CREATE TABLE task (
    task_id INT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    status ENUM('todo', 'in_progress', 'done', 'cancelled') DEFAULT 'todo',
    priority ENUM('low', 'medium', 'high', 'critical') DEFAULT 'medium',
    assigned_to INT,
    FOREIGN KEY (assigned_to) REFERENCES customer(customer_id)
);

-- View (read-only)
CREATE VIEW active_customers AS
    SELECT customer_id, first_name, last_name, email
    FROM customer WHERE active = true;

-- Seed data
INSERT INTO customer (first_name, last_name, email, active) VALUES
    ('Alice', 'Smith', 'alice@example.com', true),
    ('Bob', 'Jones', 'bob@example.com', true),
    ('Carol', 'Williams', 'carol@example.com', false),
    ('David', 'Brown', 'david@example.com', true),
    ('Eve', 'Davis', 'eve@example.com', true);

INSERT INTO orders (customer_id, total_amount) VALUES
    (1, 100.50),
    (1, 250.00),
    (2, 75.25),
    (3, 300.00),
    (4, 50.00);

INSERT INTO order_items (order_id, product_id, quantity, price) VALUES
    (1, 1, 2, 25.25),
    (1, 2, 1, 50.00),
    (2, 3, 5, 50.00),
    (3, 1, 1, 75.25),
    (4, 4, 3, 100.00);

INSERT INTO task (title, status, priority, assigned_to) VALUES
    ('Fix bug', 'done', 'high', 1),
    ('Write docs', 'todo', 'low', 2),
    ('Deploy', 'in_progress', 'critical', 1);
