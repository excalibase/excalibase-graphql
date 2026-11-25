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

-- two composite keys that share their first column and differ in the second
CREATE TABLE tparent (tenant INT, id INT, name TEXT NOT NULL, PRIMARY KEY (tenant, id));
INSERT INTO tparent VALUES (1, 1, 'p1'), (1, 2, 'p2'), (2, 1, 'q1');
CREATE TABLE tchild (id INT PRIMARY KEY, tenant INT, a_id INT, b_id INT,
                     FOREIGN KEY (tenant, a_id) REFERENCES tparent (tenant, id),
                     FOREIGN KEY (tenant, b_id) REFERENCES tparent (tenant, id));
INSERT INTO tchild VALUES (1, 1, 1, 2), (2, 1, 2, 1);
