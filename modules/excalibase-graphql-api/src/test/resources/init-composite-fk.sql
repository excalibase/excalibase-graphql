-- Composite keys whose first column does not identify the parent on its own.
CREATE TABLE parent (a INT, b INT, name TEXT NOT NULL, PRIMARY KEY (a, b));
INSERT INTO parent VALUES (1, 1, 'p11'), (1, 2, 'p12'), (2, 1, 'p21');

-- one composite key
CREATE TABLE line (id INT PRIMARY KEY, pa INT, pb INT, FOREIGN KEY (pa, pb) REFERENCES parent (a, b));
INSERT INTO line VALUES (1, 1, 2), (2, 1, 1), (3, 2, 1);

-- two composite keys to the same parent
CREATE TABLE transfer (id INT PRIMARY KEY, from_a INT, from_b INT, to_a INT, to_b INT,
                       FOREIGN KEY (from_a, from_b) REFERENCES parent (a, b),
                       FOREIGN KEY (to_a, to_b) REFERENCES parent (a, b));
INSERT INTO transfer VALUES (1, 1, 2, 2, 1);
