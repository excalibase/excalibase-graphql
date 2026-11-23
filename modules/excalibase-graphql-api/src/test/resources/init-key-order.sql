-- Columns declared in an order that is neither alphabetical nor by name length.
CREATE TABLE zebra (id INT PRIMARY KEY, zulu TEXT, alpha_long_name TEXT, mid INT, b TEXT);
CREATE TABLE stripe (id INT PRIMARY KEY, zebra_id INT REFERENCES zebra (id), whiskey TEXT, a TEXT, long_column_name TEXT);
INSERT INTO zebra VALUES (1, 'z', 'a', 5, 'b');
INSERT INTO stripe VALUES (1, 1, 'w', 'a', 'l');
