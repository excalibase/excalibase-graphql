CREATE TABLE ledger (
    id INTEGER PRIMARY KEY,
    amount NUMERIC(30, 10),
    big_id BIGINT,
    blob_col BYTEA,
    meta JSONB
);
INSERT INTO ledger (id, amount, big_id, blob_col, meta) VALUES
    (1, 12345678901234567890.1234567891, 9223372036854775807, '\x00ff10', '{"a": 1}');

CREATE TABLE tallies (
    id INTEGER PRIMARY KEY,
    counts BIGINT[],
    prices NUMERIC(10, 2)[]
);
INSERT INTO tallies (id, counts, prices) VALUES (1, '{9223372036854775807, 2}', '{1.50, 2.25}');

-- Wider than one jsonb_build_object call takes (100 arguments, so 50 columns)
DO $$
BEGIN
    EXECUTE 'CREATE TABLE wide (id INTEGER PRIMARY KEY, total NUMERIC(12, 2), '
        || (SELECT string_agg('c' || n || ' INTEGER DEFAULT ' || n, ', ') FROM generate_series(1, 60) n) || ')';
END $$;
INSERT INTO wide (id, total) VALUES (1, 10.50);
