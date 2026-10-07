-- Fixture for EXC-559: every kind of database refusal a client can cause, so the data API answers each
-- with its own 4xx instead of a 500.

CREATE TABLE customers (
    id    INT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE
);

INSERT INTO customers (id, email) VALUES (1, 'alice@example.com'), (2, 'bob@example.com');

CREATE TABLE orders (
    id          INT PRIMARY KEY,
    customer_id INT REFERENCES customers (id),
    qty         INT NOT NULL CHECK (qty > 0),
    ref         UUID,
    placed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    owner_id    TEXT
);

INSERT INTO orders (id, customer_id, qty, ref) VALUES (1, 1, 2, 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11');

CREATE FUNCTION refuse_large_orders() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.qty > 100 THEN
        RAISE EXCEPTION 'Order quantity % is above the limit', NEW.qty;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER orders_limit BEFORE INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION refuse_large_orders();

CREATE TABLE bookings (
    id     INT PRIMARY KEY,
    during TSRANGE NOT NULL,
    EXCLUDE USING gist (during WITH &&)
);

INSERT INTO bookings (id, during) VALUES (1, '[2026-01-01 10:00, 2026-01-01 11:00)');
