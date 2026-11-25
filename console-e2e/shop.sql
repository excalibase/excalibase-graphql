-- Tables for the console's Schema page: two keys, so it has relationships to show. Safe to run again.
CREATE TABLE IF NOT EXISTS public.customers (
    id   bigserial PRIMARY KEY,
    name text NOT NULL
);
CREATE TABLE IF NOT EXISTS public.orders (
    id            bigserial PRIMARY KEY,
    customer_id   bigint  NOT NULL REFERENCES public.customers (id),
    status        text    NOT NULL DEFAULT 'pending',
    total         numeric(10, 2) NOT NULL DEFAULT 0,
    internal_note text
);
CREATE TABLE IF NOT EXISTS public.order_items (
    id       bigserial PRIMARY KEY,
    order_id bigint  NOT NULL REFERENCES public.orders (id),
    sku      text    NOT NULL,
    quantity integer NOT NULL DEFAULT 1
);
INSERT INTO public.customers (name) SELECT 'Ada' WHERE NOT EXISTS (SELECT 1 FROM public.customers);
