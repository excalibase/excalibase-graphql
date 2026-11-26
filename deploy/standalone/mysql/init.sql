-- Runs once, when the MySQL data folder is empty.
CREATE TABLE todos (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_id VARCHAR(64) NOT NULL,
  title    VARCHAR(255) NOT NULL,
  done     BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO todos (owner_id, title) VALUES ('alice', 'write the rules'), ('bob', 'read the rules');
