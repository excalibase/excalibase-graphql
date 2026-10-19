CREATE TABLE ledger (
    id INTEGER PRIMARY KEY,
    amount NUMERIC(30, 10),
    big_id BIGINT,
    blob_col BYTEA,
    meta JSONB
);
INSERT INTO ledger (id, amount, big_id, blob_col, meta) VALUES
    (1, 12345678901234567890.1234567891, 9223372036854775807, '\x00ff10', '{"a": 1}');
