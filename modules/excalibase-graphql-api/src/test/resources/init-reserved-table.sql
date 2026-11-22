-- A table named like a route word: the REST path /{project}/api/v1/graphql must be this table, guarded like any other.
CREATE TABLE graphql (
    id    INT PRIMARY KEY,
    token TEXT NOT NULL
);
INSERT INTO graphql (id, token) VALUES (1, 'shhh');

CREATE TABLE orders (
    id INT PRIMARY KEY
);
INSERT INTO orders (id) VALUES (1);
