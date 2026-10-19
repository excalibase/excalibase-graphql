CREATE TABLE goods (
    id INT AUTO_INCREMENT PRIMARY KEY,
    sku VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    cost DECIMAL(10, 2),
    kind ENUM('food', 'drink') NOT NULL
);
INSERT INTO goods (sku, name, cost, kind) VALUES
    ('A-1', 'Apple', 1.50, 'food'),
    ('B-2', '100% Juice', NULL, 'drink'),
    ('C-3', 'snake_case', 3.00, 'food'),
    ('D-4', 'Bread', NULL, 'food'),
    ('E-5', 'Cola', 2.00, 'drink');
