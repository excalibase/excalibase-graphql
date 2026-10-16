-- MySQL project with permissions on (EXC-618). No database-level enforcement: the engine compiles every
-- permission into the SQL it sends, so the connecting user sees every row without it.

CREATE TABLE notes (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    owner_id   CHAR(36),
    title      VARCHAR(100),
    status     VARCHAR(20) NOT NULL DEFAULT 'open',
    pinned     TINYINT(1) NOT NULL DEFAULT 0
) AUTO_INCREMENT = 10000;

-- A key the client supplies: written rows are read back by it, not by LAST_INSERT_ID().
CREATE TABLE tags (
    code  VARCHAR(10) PRIMARY KEY,
    label VARCHAR(50)
);

-- A key nobody supplies and the database does not generate by AUTO_INCREMENT.
CREATE TABLE stamps (
    id    CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    label VARCHAR(50)
);

-- Keys every column defaults: an insert of no column at all.
CREATE TABLE counters (
    id    INT AUTO_INCREMENT PRIMARY KEY,
    label VARCHAR(20) NOT NULL DEFAULT 'x'
);

-- A key MySQL does not generate beside a column it does: the generated value is not the key.
CREATE TABLE sequenced (
    code  INT PRIMARY KEY DEFAULT 7,
    n     INT AUTO_INCREMENT UNIQUE,
    label VARCHAR(20)
);

-- Writable by whoever owns some board: the permission reads the table it writes (MySQL error 1093 if direct).
CREATE TABLE boards (
    id       INT PRIMARY KEY,
    owner_id CHAR(36),
    name     VARCHAR(50)
);

-- Written by a role that may insert but never read: its rows are keyed by a column the role cannot see.
CREATE TABLE feedback (
    id   INT AUTO_INCREMENT PRIMARY KEY,
    body VARCHAR(200)
);

INSERT INTO boards (id, owner_id, name) VALUES (1, '22222222-2222-2222-2222-222222222222', 'bob-board');

INSERT INTO notes (id, owner_id, title, status) VALUES
    (1, '11111111-1111-1111-1111-111111111111', 'alice-1', 'open'),
    (2, '22222222-2222-2222-2222-222222222222', 'bob-1', 'open'),
    (3, '11111111-1111-1111-1111-111111111111', 'alice-hidden', 'hidden');
