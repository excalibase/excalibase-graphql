CREATE TABLE items (
    id INT PRIMARY KEY,
    name VARCHAR(40) NOT NULL,
    note VARCHAR(40),
    active BOOLEAN NOT NULL,
    qty INT,
    created_at TIMESTAMP NOT NULL,
    day DATE
);
SET time_zone = '+00:00';
INSERT INTO items (id, name, note, active, qty, created_at, day) VALUES
    (1, 'Tea', 'loose leaf', TRUE, 3, '2026-10-19 14:30:36', '2026-10-19'),
    (2, 'Milk', NULL, FALSE, NULL, '2026-10-01 10:00:00', NULL);
