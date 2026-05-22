-- Study-cases: shopify (ecommerce) RLS + role switching for the storefront demo.
--
-- Layered on top of init-shopify-users.sql (01) and initdb-ecommerce.sql (02).
-- Three customer-facing roles map to three personas:
--   app_anon          — public visitor; can browse products, categories, reviews
--   app_authenticated — signed-in customer; can place orders, write reviews,
--                       see only their own orders/wishlist
--   app_admin         — operator; full RW on inventory + orders
--   app_service       — back-end (BYPASSRLS), used by trusted ETL/cron only

-- ---------------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------------
CREATE ROLE app_anon          NOLOGIN;
CREATE ROLE app_authenticated NOLOGIN;
CREATE ROLE app_admin         NOLOGIN;
CREATE ROLE app_service       NOLOGIN BYPASSRLS;

GRANT app_anon, app_authenticated, app_admin, app_service TO excalibase_app;
GRANT app_anon, app_authenticated, app_admin, app_service TO app_user;

GRANT USAGE ON SCHEMA shopify TO app_anon, app_authenticated, app_admin, app_service;

-- ---------------------------------------------------------------------------
-- Helper: the JWT carries `userId` (numeric), and the storefront seed data
-- below inserts customers with matching ids. So we just cast — same shape
-- as the kanban demo's request.user_id flow.
-- ---------------------------------------------------------------------------
CREATE FUNCTION shopify.current_customer_id() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER AS $$
    SELECT NULLIF(current_setting('request.user_id', true), '')::integer
$$;

-- whoami_view — same role-indicator trick as kanban demo.
CREATE VIEW shopify.whoami_view AS SELECT current_user::text AS role;

GRANT EXECUTE ON FUNCTION shopify.current_customer_id() TO app_anon, app_authenticated, app_admin;
GRANT SELECT ON shopify.whoami_view TO app_anon, app_authenticated, app_admin, app_service;

-- ---------------------------------------------------------------------------
-- GRANTs
-- ---------------------------------------------------------------------------
-- Anon: read-only catalog + reviews (no customer/order/payment data).
GRANT SELECT ON shopify.categories, shopify.products, shopify.product_variants,
                shopify.reviews
    TO app_anon;

-- Authenticated: same catalog access + write own reviews/orders/wishlist.
GRANT SELECT ON shopify.categories, shopify.products, shopify.product_variants,
                shopify.customers, shopify.orders, shopify.order_items,
                shopify.payments, shopify.reviews, shopify.wishlists
    TO app_authenticated;
GRANT INSERT, UPDATE ON shopify.reviews, shopify.orders, shopify.order_items,
                        shopify.wishlists
    TO app_authenticated;

-- Admin: full RW on operational tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA shopify TO app_admin;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA shopify
    TO app_authenticated, app_admin, app_service;

-- ---------------------------------------------------------------------------
-- Enable RLS
-- ---------------------------------------------------------------------------
ALTER TABLE shopify.products         ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify.product_variants ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify.categories       ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify.customers        ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify.orders           ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify.order_items      ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify.payments         ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify.reviews          ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify.wishlists        ENABLE ROW LEVEL SECURITY;

ALTER TABLE shopify.customers FORCE ROW LEVEL SECURITY;
ALTER TABLE shopify.orders    FORCE ROW LEVEL SECURITY;
ALTER TABLE shopify.payments  FORCE ROW LEVEL SECURITY;
ALTER TABLE shopify.wishlists FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- Catalog: products + variants + categories — same visible set for anon and
-- authenticated. Admin sees deleted_at rows too.
-- ---------------------------------------------------------------------------
CREATE POLICY products_public ON shopify.products
    FOR SELECT TO app_anon, app_authenticated
    USING (deleted_at IS NULL);
CREATE POLICY products_admin ON shopify.products
    FOR ALL TO app_admin USING (true);

CREATE POLICY variants_public ON shopify.product_variants
    FOR SELECT TO app_anon, app_authenticated
    USING (true);
CREATE POLICY variants_admin ON shopify.product_variants
    FOR ALL TO app_admin USING (true);

CREATE POLICY categories_public ON shopify.categories
    FOR SELECT TO app_anon, app_authenticated, app_admin USING (true);

-- ---------------------------------------------------------------------------
-- Reviews: anyone reads; only the author can write their own.
-- ---------------------------------------------------------------------------
CREATE POLICY reviews_public_read ON shopify.reviews
    FOR SELECT TO app_anon, app_authenticated, app_admin USING (true);

CREATE POLICY reviews_own_write ON shopify.reviews
    FOR INSERT TO app_authenticated
    WITH CHECK (customer_id = (select shopify.current_customer_id()));

CREATE POLICY reviews_own_update ON shopify.reviews
    FOR UPDATE TO app_authenticated
    USING (customer_id = (select shopify.current_customer_id()))
    WITH CHECK (customer_id = (select shopify.current_customer_id()));

CREATE POLICY reviews_admin ON shopify.reviews
    FOR ALL TO app_admin USING (true);

-- ---------------------------------------------------------------------------
-- Customer-private data — orders, order_items, payments, wishlists.
-- Authenticated user sees only their own; admin sees everything.
-- ---------------------------------------------------------------------------
CREATE POLICY customers_own ON shopify.customers
    FOR SELECT TO app_authenticated
    USING (id = (select shopify.current_customer_id()));
CREATE POLICY customers_admin ON shopify.customers
    FOR ALL TO app_admin USING (true);

CREATE POLICY orders_own ON shopify.orders
    FOR ALL TO app_authenticated
    USING (customer_id = (select shopify.current_customer_id()))
    WITH CHECK (customer_id = (select shopify.current_customer_id()));
CREATE POLICY orders_admin ON shopify.orders
    FOR ALL TO app_admin USING (true);

CREATE POLICY order_items_via_order ON shopify.order_items
    FOR ALL TO app_authenticated
    USING (order_id IN (SELECT id FROM shopify.orders))
    WITH CHECK (order_id IN (SELECT id FROM shopify.orders));
CREATE POLICY order_items_admin ON shopify.order_items
    FOR ALL TO app_admin USING (true);

CREATE POLICY payments_via_order ON shopify.payments
    FOR SELECT TO app_authenticated
    USING (order_id IN (SELECT id FROM shopify.orders));
CREATE POLICY payments_admin ON shopify.payments
    FOR ALL TO app_admin USING (true);

CREATE POLICY wishlists_own ON shopify.wishlists
    FOR ALL TO app_authenticated
    USING (customer_id = (select shopify.current_customer_id()))
    WITH CHECK (customer_id = (select shopify.current_customer_id()));
CREATE POLICY wishlists_admin ON shopify.wishlists
    FOR ALL TO app_admin USING (true);

-- ---------------------------------------------------------------------------
-- Demo customers — IDs hand-picked to match the JWT userId values our
-- sign-tokens.mjs emits (alice=1, bob=2, carol=3, admin=1). Same userId
-- values as the kanban demo so cross-demo identity feels coherent.
-- ---------------------------------------------------------------------------
INSERT INTO shopify.customers (id, email, name, phone) VALUES
    (1, 'alice@acme.com',  'Alice Chen',  '+1-555-0100'),
    (2, 'bob@acme.com',    'Bob Kumar',   '+1-555-0101'),
    (3, 'carol@acme.com',  'Carol Park',  '+1-555-0102')
ON CONFLICT (id) DO NOTHING;
-- Bump the sequence past hand-picked IDs.
SELECT setval('shopify.customers_id_seq', GREATEST(3, (SELECT MAX(id) FROM shopify.customers)));

-- Seed a couple of orders + reviews so anon/auth views aren't empty.
INSERT INTO shopify.orders (customer_id, status, total, notes) VALUES
    (1, 'paid',     1098.00, 'Gift wrap please'),
    (1, 'pending',   249.99, NULL),
    (3, 'shipped',   899.00, NULL)
ON CONFLICT DO NOTHING;

INSERT INTO shopify.reviews (product_id, customer_id, rating, title, body) VALUES
    (1, 1, 5, 'Amazing phone',     'Battery life is finally good. Camera is fantastic.'),
    (2, 3, 4, 'Great laptop',      'Build quality is impeccable. Battery could be better.'),
    (1, 2, 3, 'Solid but pricey',  'Good but the price tag is steep.')
ON CONFLICT DO NOTHING;
