-- two composite keys over the same child columns, to different parent keys: only the constraint tells them apart
CREATE TABLE gparent (a INT, b INT, c INT, d INT, name VARCHAR(20) NOT NULL, PRIMARY KEY (a, b), UNIQUE KEY gparent_cd (c, d));
INSERT INTO gparent VALUES (1, 2, 3, 4, 'one'), (3, 4, 1, 2, 'two');
CREATE TABLE gchild (id INT PRIMARY KEY, x INT, y INT,
                     CONSTRAINT gchild_by_pk FOREIGN KEY (x, y) REFERENCES gparent (a, b),
                     CONSTRAINT gchild_by_alt FOREIGN KEY (x, y) REFERENCES gparent (c, d));
INSERT INTO gchild VALUES (1, 1, 2), (2, 3, 4);
