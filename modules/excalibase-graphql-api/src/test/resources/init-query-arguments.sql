-- Fixture for query argument handling (EXC-646): orderBy, in, limit and offset, same rows on Postgres and MySQL.
CREATE TABLE products (
    id       INT PRIMARY KEY,
    name     VARCHAR(20) NOT NULL,
    category VARCHAR(20) NOT NULL
);

INSERT INTO products (id, name, category) VALUES
    (1, 'a', 'x'), (2, 'b', 'y'), (3, 'c', 'x'), (4, 'd', 'y'), (5, 'e', 'x');
