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

-- two composite keys over the same child columns, to different parent keys: only the constraint tells them apart
CREATE TABLE gparent (a INT, b INT, c INT, d INT, name TEXT NOT NULL, PRIMARY KEY (a, b), UNIQUE (c, d));
INSERT INTO gparent VALUES (1, 2, 3, 4, 'one'), (3, 4, 1, 2, 'two');
CREATE TABLE gchild (id INT PRIMARY KEY, x INT, y INT,
                     CONSTRAINT gchild_by_pk FOREIGN KEY (x, y) REFERENCES gparent (a, b),
                     CONSTRAINT gchild_by_alt FOREIGN KEY (x, y) REFERENCES gparent (c, d));
INSERT INTO gchild VALUES (1, 1, 2), (2, 3, 4);

-- the same key declared twice is one relationship, single-column and composite
CREATE TABLE sparent (id INT PRIMARY KEY);
INSERT INTO sparent VALUES (1);
CREATE TABLE schild (id INT PRIMARY KEY, sid INT,
                     CONSTRAINT schild_one FOREIGN KEY (sid) REFERENCES sparent (id),
                     CONSTRAINT schild_two FOREIGN KEY (sid) REFERENCES sparent (id));
INSERT INTO schild VALUES (1, 1);
CREATE TABLE dupchild (id INT PRIMARY KEY, pa INT, pb INT,
                       CONSTRAINT dup_one FOREIGN KEY (pa, pb) REFERENCES parent (a, b),
                       CONSTRAINT dup_two FOREIGN KEY (pa, pb) REFERENCES parent (a, b));
INSERT INTO dupchild VALUES (1, 1, 2);

-- constraint names are unique per table, not per schema: two tables may both have fk_user
CREATE TABLE ua_parent (id INT PRIMARY KEY, label TEXT);
CREATE TABLE ub_parent (id INT PRIMARY KEY, label TEXT);
INSERT INTO ua_parent VALUES (1, 'from a');
INSERT INTO ub_parent VALUES (1, 'from b');
CREATE TABLE fk_a (id INT PRIMARY KEY, uid INT, CONSTRAINT fk_user FOREIGN KEY (uid) REFERENCES ua_parent (id));
CREATE TABLE fk_b (id INT PRIMARY KEY, uid INT, CONSTRAINT fk_user FOREIGN KEY (uid) REFERENCES ub_parent (id));
INSERT INTO fk_a VALUES (1, 1);
INSERT INTO fk_b VALUES (1, 1);
