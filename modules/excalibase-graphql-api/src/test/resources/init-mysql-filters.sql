CREATE TABLE article (
    id    INT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    body  TEXT,
    meta  JSON,
    FULLTEXT KEY ft_body (body)
);

INSERT INTO article (title, body, meta) VALUES
    ('Alpha', 'the quick brown fox jumps', '{"a": 1, "tags": ["x"]}'),
    ('beta', 'lazy brown dogs sleep', '{"b": 2}'),
    ('Gamma', 'quick silver fox', '{"a": 1, "b": 2}');
