-- Study-cases: clinic role-switching bootstrap.
--
-- Layered on top of init-clinic-users.sql (01) and initdb-clinic.sql (02).
-- The clinic study case has no RLS policies — these roles exist only so the
-- globally-enabled role-switching feature can issue SET LOCAL ROLE without
-- erroring. Every role gets full access to the clinic schema (no row
-- filtering); clinic is a plain query/mutation showcase, not an RLS demo.

CREATE ROLE app_anon          NOLOGIN;
CREATE ROLE app_authenticated NOLOGIN;
CREATE ROLE app_admin         NOLOGIN;
CREATE ROLE app_service       NOLOGIN BYPASSRLS;

-- The pool login users need membership in every role so excalibase can
-- SET LOCAL ROLE to whichever role the JWT scope resolves to.
GRANT app_anon, app_authenticated, app_admin, app_service TO excalibase_app;
GRANT app_anon, app_authenticated, app_admin, app_service TO app_user;

GRANT USAGE ON SCHEMA clinic
    TO app_anon, app_authenticated, app_admin, app_service;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA clinic
    TO app_anon, app_authenticated, app_admin, app_service;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA clinic
    TO app_anon, app_authenticated, app_admin, app_service;
