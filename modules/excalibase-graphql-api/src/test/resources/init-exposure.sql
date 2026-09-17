-- Fixture for the exposure/grant filter. Two schemas hold a same-named table so
-- an ambiguous bare grant has something real to be ambiguous about, and a
-- set-returning function gives the "function needs SELECT on its return table"
-- rule something to bite on.

CREATE TABLE orders (
    id       INT PRIMARY KEY,
    customer TEXT NOT NULL,
    total    NUMERIC NOT NULL
);

INSERT INTO orders (id, customer, total) VALUES
    (1, 'alice', 10.00),
    (2, 'bob',   20.00);

CREATE TABLE secrets (
    id    INT PRIMARY KEY,
    token TEXT NOT NULL
);

INSERT INTO secrets (id, token) VALUES (1, 'shhh');

CREATE TABLE customer (
    id   INT PRIMARY KEY,
    name TEXT NOT NULL
);

INSERT INTO customer (id, name) VALUES (1, 'alice');

CREATE SCHEMA billing;

CREATE TABLE billing.customer (
    id   INT PRIMARY KEY,
    plan TEXT NOT NULL
);

INSERT INTO billing.customer (id, plan) VALUES (1, 'pro');

CREATE FUNCTION recent_orders() RETURNS SETOF orders
    LANGUAGE sql STABLE AS $$ SELECT * FROM orders $$;

CREATE FUNCTION leaky_secrets() RETURNS SETOF secrets
    LANGUAGE sql STABLE AS $$ SELECT * FROM secrets $$;
