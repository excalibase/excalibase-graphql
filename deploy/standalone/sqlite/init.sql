-- Run once against the file before the engine starts:  sqlite3 data/app.db < init.sql
CREATE TABLE IF NOT EXISTS todos (
    id       INTEGER PRIMARY KEY,
    owner_id TEXT NOT NULL,
    title    TEXT NOT NULL,
    done     BOOLEAN NOT NULL DEFAULT 0
);
