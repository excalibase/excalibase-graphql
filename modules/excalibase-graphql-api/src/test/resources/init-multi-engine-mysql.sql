CREATE TABLE gadget (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    in_stock TINYINT(1) NOT NULL DEFAULT 1,
    size ENUM('small', 'large') NOT NULL DEFAULT 'small',
    made_at DATETIME NULL,
    seen_at TIMESTAMP(3) NULL
);

-- A key the client sends: no AUTO_INCREMENT.
CREATE TABLE sku (
    code VARCHAR(20) PRIMARY KEY,
    label VARCHAR(50) NOT NULL
);

CREATE TABLE stock (
    warehouse VARCHAR(20) NOT NULL,
    code VARCHAR(20) NOT NULL,
    qty INT NOT NULL,
    PRIMARY KEY (warehouse, code)
);

INSERT INTO gadget (name, in_stock, size, made_at, seen_at) VALUES
    ('Sprocket', 1, 'large', '2026-01-02 03:04:05', '2026-01-02 03:04:05.250'),
    ('Cog', 0, 'small', NULL, NULL);

INSERT INTO sku (code, label) VALUES ('A-1', 'first');

INSERT INTO stock (warehouse, code, qty) VALUES ('north', 'A-1', 5), ('south', 'A-1', 7);
