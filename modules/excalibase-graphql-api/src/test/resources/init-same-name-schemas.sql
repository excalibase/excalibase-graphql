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

-- two keys of one table into the two users tables: a bare `users` embed cannot tell them apart
CREATE TABLE tenant_a.audit (id INT PRIMARY KEY, a_user INT REFERENCES tenant_a.users (id),
                             b_user INT REFERENCES tenant_b.users (id));
-- two children called logs, in two schemas, of tenant_a.users: a bare `logs` embed cannot tell them apart
CREATE TABLE tenant_a.logs (id INT PRIMARY KEY, user_id INT REFERENCES tenant_a.users (id));
CREATE TABLE tenant_b.logs (id INT PRIMARY KEY, user_id INT REFERENCES tenant_a.users (id));

-- two keys of one table into the one users table: a bare `users` embed cannot tell them apart
CREATE TABLE tenant_a.docs (id INT PRIMARY KEY, created_by INT REFERENCES tenant_a.users (id),
                            updated_by INT REFERENCES tenant_a.users (id));

-- keys into both users tables, two of them into the same one: no bare or qualified table name alone picks one
CREATE TABLE tenant_a.mixed (id INT PRIMARY KEY, a_user INT REFERENCES tenant_a.users (id),
                             created_by INT REFERENCES tenant_a.users (id),
                             b_user INT REFERENCES tenant_b.users (id));

-- two levels under users, for collisions inside a nested embed
CREATE TABLE tenant_a.profiles (id INT PRIMARY KEY, user_id INT REFERENCES tenant_a.users (id));
CREATE TABLE tenant_a.badges (id INT PRIMARY KEY, profile_id INT REFERENCES tenant_a.profiles (id));

INSERT INTO tenant_a.users VALUES (1, 'from-a');
INSERT INTO tenant_b.users VALUES (1, 'from-b');
INSERT INTO tenant_a.posts VALUES (1, 1);
INSERT INTO tenant_b.orders VALUES (1, 1);
INSERT INTO tenant_b.notes VALUES (1, 1);
INSERT INTO tenant_a.audit VALUES (1, 1, 1);
INSERT INTO tenant_a.logs VALUES (1, 1);
INSERT INTO tenant_b.logs VALUES (1, 1);
INSERT INTO tenant_a.docs VALUES (1, 1, 1);
INSERT INTO tenant_a.mixed VALUES (1, 1, 1, 1);
INSERT INTO tenant_a.profiles VALUES (1, 1);
INSERT INTO tenant_a.badges VALUES (1, 1);
