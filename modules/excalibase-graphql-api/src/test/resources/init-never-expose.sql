-- Fixture for the never-expose guard (EXC-733): the engine's internal schemas hold tables, a public
-- table keeps a foreign key into one of them, and functions in and over them return their rows.
-- public.t shares its bare name with excalibase_meta.t, to catch a name resolved without its schema.

CREATE SCHEMA excalibase_meta;
CREATE TABLE excalibase_meta.t (id INT PRIMARY KEY, secret TEXT);
INSERT INTO excalibase_meta.t VALUES (1, 'document-secret');

CREATE SCHEMA auth;
CREATE TABLE auth.users (id INT PRIMARY KEY, password_hash TEXT);
INSERT INTO auth.users VALUES (1, 'hash-secret');

CREATE TABLE public.t (id INT PRIMARY KEY, label TEXT);
INSERT INTO public.t VALUES (1, 'plain');

CREATE TABLE public.items (
    id      INT PRIMARY KEY,
    name    TEXT,
    meta_id INT REFERENCES excalibase_meta.t (id),
    user_id INT REFERENCES auth.users (id)
);
INSERT INTO public.items VALUES (1, 'visible', 1, 1);

CREATE FUNCTION excalibase_meta.read_t() RETURNS SETOF excalibase_meta.t
    LANGUAGE sql STABLE AS $$ SELECT * FROM excalibase_meta.t $$;

CREATE FUNCTION auth.read_users() RETURNS SETOF auth.users
    LANGUAGE sql STABLE AS $$ SELECT * FROM auth.users $$;

CREATE FUNCTION public.leak_meta() RETURNS SETOF excalibase_meta.t
    LANGUAGE sql STABLE AS $$ SELECT * FROM excalibase_meta.t $$;

CREATE FUNCTION public.meta_label(row_ excalibase_meta.t) RETURNS TEXT
    LANGUAGE sql STABLE AS $$ SELECT row_.secret $$;

CREATE FUNCTION public.plain_items() RETURNS SETOF public.items
    LANGUAGE sql STABLE AS $$ SELECT * FROM public.items $$;
