CREATE TYPE goods_kind AS ENUM ('food', 'drink');
CREATE TABLE goods (
    id SERIAL PRIMARY KEY,
    sku TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    cost NUMERIC(10, 2),
    kind goods_kind NOT NULL
);
INSERT INTO goods (sku, name, cost, kind) VALUES
    ('A-1', 'Apple', 1.50, 'food'),
    ('B-2', '100% Juice', NULL, 'drink'),
    ('C-3', 'snake_case', 3.00, 'food'),
    ('D-4', 'Bread', NULL, 'food'),
    ('E-5', 'Cola', 2.00, 'drink');
