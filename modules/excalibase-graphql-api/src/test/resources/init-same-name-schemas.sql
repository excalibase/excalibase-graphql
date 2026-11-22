-- Two schemas that each own a table named users; keys must follow the schema they name.
CREATE SCHEMA tenant_a;
CREATE SCHEMA tenant_b;

CREATE TABLE tenant_a.users (id INT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE tenant_b.users (id INT PRIMARY KEY, name TEXT NOT NULL);

-- inside tenant_a: posts -> tenant_a.users
CREATE TABLE tenant_a.posts (id INT PRIMARY KEY, user_id INT REFERENCES tenant_a.users (id));
-- across schemas: tenant_b.orders -> tenant_a.users, while tenant_b.users exists too
CREATE TABLE tenant_b.orders (id INT PRIMARY KEY, user_id INT REFERENCES tenant_a.users (id));
-- inside tenant_b: notes -> tenant_b.users
CREATE TABLE tenant_b.notes (id INT PRIMARY KEY, user_id INT REFERENCES tenant_b.users (id));

INSERT INTO tenant_a.users VALUES (1, 'from-a');
INSERT INTO tenant_b.users VALUES (1, 'from-b');
INSERT INTO tenant_a.posts VALUES (1, 1);
INSERT INTO tenant_b.orders VALUES (1, 1);
INSERT INTO tenant_b.notes VALUES (1, 1);
