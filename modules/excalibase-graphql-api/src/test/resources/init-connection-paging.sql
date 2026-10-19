-- Fixture for keyset cursor pagination (EXC-643): duplicate sort values and NULLs, same rows on Postgres and MySQL.
CREATE TABLE products (
    id       INT PRIMARY KEY,
    name     VARCHAR(20) NOT NULL,
    price    DECIMAL(10, 2) NOT NULL,
    category VARCHAR(20)
);

INSERT INTO products (id, name, price, category) VALUES
    (1, 'a', 10.00, 'x'),
    (2, 'b', 5.00, NULL),
    (3, 'c', 10.00, 'y'),
    (4, 'd', 7.50, 'x'),
    (5, 'e', 5.00, NULL),
    (6, 'f', 10.00, NULL),
    (7, 'g', 7.50, 'y');
