CREATE TABLE items (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    note TEXT,
    active BOOLEAN NOT NULL,
    qty INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    day DATE
);
INSERT INTO items (id, name, note, active, qty, created_at, day) VALUES
    (1, 'Tea', 'loose leaf', TRUE, 3, '2026-10-19 14:30:36+00', '2026-10-19'),
    (2, 'Milk', NULL, FALSE, NULL, '2026-10-01 10:00:00+00', NULL);
