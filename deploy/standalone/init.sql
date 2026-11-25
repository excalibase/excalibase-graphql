CREATE TABLE public.todos (
    id        serial PRIMARY KEY,
    owner_id  text    NOT NULL,
    title     text    NOT NULL,
    done      boolean NOT NULL DEFAULT false
);
INSERT INTO public.todos (owner_id, title) VALUES ('alice', 'write the rules'), ('bob', 'read the rules');
