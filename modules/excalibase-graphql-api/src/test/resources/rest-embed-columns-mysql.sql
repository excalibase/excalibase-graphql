CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50) NOT NULL, secret VARCHAR(50));
CREATE TABLE docs (id INT PRIMARY KEY, title VARCHAR(50), created_by INT, updated_by INT,
  CONSTRAINT fk_created FOREIGN KEY (created_by) REFERENCES users (id),
  CONSTRAINT fk_updated FOREIGN KEY (updated_by) REFERENCES users (id));
INSERT INTO users VALUES (1, 'alice', 's1'), (2, 'bob', 's2');
INSERT INTO docs VALUES (1, 'doc-title', 1, 2);
