CREATE SCHEMA IF NOT EXISTS filters;

CREATE TYPE filters.mood AS ENUM ('HAPPY', 'SAD');

CREATE TABLE filters.article (
    article_id  SERIAL PRIMARY KEY,
    title       TEXT NOT NULL,
    slug        VARCHAR(80),
    views       INTEGER,
    big_views   BIGINT,
    rating      NUMERIC(4, 2),
    score       DOUBLE PRECISION,
    published   BOOLEAN DEFAULT false,
    created_at  TIMESTAMPTZ DEFAULT now(),
    published_on DATE,
    external_id UUID,
    meta        JSONB,
    raw_meta    JSON,
    tags        TEXT[],
    mood        filters.mood,
    search_vec  TSVECTOR
);

CREATE TABLE filters.comment (
    comment_id SERIAL PRIMARY KEY,
    article_id INTEGER REFERENCES filters.article(article_id),
    body       TEXT
);

INSERT INTO filters.article (title, slug, views, meta, mood, search_vec) VALUES
    ('Quick fox', 'quick-fox', 10, '{"a": 1, "tags": ["x"]}', 'HAPPY', to_tsvector('quick brown fox')),
    ('Lazy dog', 'lazy-dog', 20, '{"b": 2}', 'SAD', to_tsvector('lazy dog sleeps'));

INSERT INTO filters.comment (article_id, body) VALUES (1, 'first'), (1, 'second'), (2, 'third');
