CREATE TABLE ledger (
    id INT PRIMARY KEY,
    amount DECIMAL(30, 10),
    big_id BIGINT UNSIGNED,
    blob_col VARBINARY(8),
    meta JSON,
    span TIME,
    flags BIT(8)
);
INSERT INTO ledger (id, amount, big_id, blob_col, meta, span, flags) VALUES
    (1, 12345678901234567890.1234567891, 18446744073709551615, 0x00FF10, '{"a": 1}', '838:59:59', b'10100101');
