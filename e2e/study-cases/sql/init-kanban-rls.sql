-- Study-cases: kanban RLS policies + role switching for the live demo.
--
-- Layered on top of init-kanban-users.sql (01) and initdb-kanban.sql (02).
-- Establishes the four-role model:
--   app_anon          — no JWT (publishable key); read public projects only
--   app_authenticated — scope=authenticated; read all in own org, write own data
--   app_admin         — scope=authenticated + role=app_admin; full RW in own org
--   app_service       — scope=service (BYPASSRLS); back-end only
--
-- Demonstrates  on the data plane: forgetting to
-- write an RLS policy on a table becomes "permission denied" at GRANT layer
-- instead of a silent leak.

-- ---------------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------------
CREATE ROLE app_anon          NOLOGIN;
CREATE ROLE app_authenticated NOLOGIN;
CREATE ROLE app_admin         NOLOGIN;
CREATE ROLE app_service       NOLOGIN BYPASSRLS;

-- The pool login users (both app_user and excalibase_app exist after 01-users)
-- need membership in every role so excalibase can SET LOCAL ROLE to any of them.
GRANT app_anon, app_authenticated, app_admin, app_service TO excalibase_app;
GRANT app_anon, app_authenticated, app_admin, app_service TO app_user;

GRANT USAGE ON SCHEMA kanban TO app_anon, app_authenticated, app_admin, app_service;

-- ---------------------------------------------------------------------------
-- Schema additions: visibility flag + helper function
-- ---------------------------------------------------------------------------
ALTER TABLE kanban.projects ADD COLUMN is_public boolean NOT NULL DEFAULT false;

-- Mark two of the three seeded projects as publicly browsable.
UPDATE kanban.projects SET is_public = true WHERE key IN ('PLAT', 'MVP');

CREATE INDEX projects_public_idx ON kanban.projects(is_public) WHERE is_public;

-- Helper: lookup the authenticated user's org via JWT-set request.user_id.
-- SECURITY DEFINER so policies don't recurse into kanban.users RLS.
-- STABLE so the planner caches the lookup per query.
CREATE FUNCTION kanban.current_user_org() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER AS $$
    SELECT org_id FROM kanban.users
    WHERE id = NULLIF(current_setting('request.user_id', true), '')::integer
    LIMIT 1
$$;

-- whoami() — returns the resolved Postgres role for the live footer indicator.
CREATE FUNCTION kanban.whoami() RETURNS text
LANGUAGE sql STABLE AS $$ SELECT current_user::text $$;

-- whoami_view: same value as whoami(), but exposed as a view so REST GET
-- routes through executeInTx → setRlsContext → SET LOCAL ROLE. The RPC
-- handler in RestApiController doesn't set RLS context (pre-existing gap),
-- so we use a view instead for the live role indicator.
CREATE VIEW kanban.whoami_view AS SELECT current_user::text AS role;

GRANT EXECUTE ON FUNCTION kanban.current_user_org() TO app_anon, app_authenticated, app_admin;
GRANT EXECUTE ON FUNCTION kanban.whoami() TO app_anon, app_authenticated, app_admin, app_service;
GRANT SELECT ON kanban.whoami_view TO app_anon, app_authenticated, app_admin, app_service;

-- ---------------------------------------------------------------------------
-- GRANTs — defense-in-depth fail-closed at the SQL layer
-- ---------------------------------------------------------------------------
-- Read-only for anon: only public-discoverable tables. Anon never sees users,
-- time_entries, or attachments — those have NO grant, so SQL rejects with
-- "permission denied" before RLS even runs.
GRANT SELECT ON kanban.projects, kanban.issues, kanban.comments, kanban.labels,
                kanban.issue_labels, kanban.sprints
    TO app_anon;

-- Authenticated + admin can write the user-generated tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON kanban.issues, kanban.comments
    TO app_authenticated, app_admin;
GRANT SELECT ON kanban.projects, kanban.labels, kanban.issue_labels,
                kanban.sprints, kanban.users, kanban.time_entries
    TO app_authenticated, app_admin;
GRANT INSERT, UPDATE, DELETE ON kanban.time_entries TO app_authenticated, app_admin;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA kanban
    TO app_authenticated, app_admin, app_service;

-- ---------------------------------------------------------------------------
-- RLS policies — anon read public + authenticated read own-org, write own-data
-- ---------------------------------------------------------------------------
ALTER TABLE kanban.projects     ENABLE ROW LEVEL SECURITY;
ALTER TABLE kanban.issues       ENABLE ROW LEVEL SECURITY;
ALTER TABLE kanban.comments     ENABLE ROW LEVEL SECURITY;
ALTER TABLE kanban.users        ENABLE ROW LEVEL SECURITY;
ALTER TABLE kanban.time_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE kanban.labels       ENABLE ROW LEVEL SECURITY;
ALTER TABLE kanban.issue_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE kanban.sprints      ENABLE ROW LEVEL SECURITY;

ALTER TABLE kanban.projects     FORCE ROW LEVEL SECURITY;
ALTER TABLE kanban.issues       FORCE ROW LEVEL SECURITY;
ALTER TABLE kanban.comments     FORCE ROW LEVEL SECURITY;
ALTER TABLE kanban.users        FORCE ROW LEVEL SECURITY;
ALTER TABLE kanban.time_entries FORCE ROW LEVEL SECURITY;

-- ── projects ────────────────────────────────────────────────────────────────
-- Anon: only is_public = true. Authenticated: own org OR public. Admin: own org.
CREATE POLICY projects_anon_read ON kanban.projects
    FOR SELECT TO app_anon
    USING (is_public);

CREATE POLICY projects_authenticated_read ON kanban.projects
    FOR SELECT TO app_authenticated, app_admin
    USING (is_public OR org_id = (select kanban.current_user_org()));

-- ── issues ──────────────────────────────────────────────────────────────────
-- Anon: only issues whose project is public. Authenticated: own-org issues.
CREATE POLICY issues_anon_read ON kanban.issues
    FOR SELECT TO app_anon
    USING (project_id IN (SELECT id FROM kanban.projects WHERE is_public));

CREATE POLICY issues_authenticated_read ON kanban.issues
    FOR SELECT TO app_authenticated, app_admin
    USING (project_id IN (
        SELECT id FROM kanban.projects
        WHERE is_public OR org_id = (select kanban.current_user_org())
    ));

-- Authenticated can create issues only inside their own org's projects.
CREATE POLICY issues_authenticated_write ON kanban.issues
    FOR INSERT TO app_authenticated
    WITH CHECK (project_id IN (
        SELECT id FROM kanban.projects WHERE org_id = (select kanban.current_user_org())
    ));

-- Authenticated can update only their own reported issues.
CREATE POLICY issues_authenticated_update ON kanban.issues
    FOR UPDATE TO app_authenticated
    USING (reporter_id = NULLIF(current_setting('request.user_id', true), '')::integer)
    WITH CHECK (reporter_id = NULLIF(current_setting('request.user_id', true), '')::integer);

-- Admin: full write within own org.
CREATE POLICY issues_admin_all ON kanban.issues
    FOR ALL TO app_admin
    USING (project_id IN (
        SELECT id FROM kanban.projects WHERE org_id = (select kanban.current_user_org())
    ));

-- ── comments ───────────────────────────────────────────────────────────────
-- Comments visible iff parent issue is visible.
CREATE POLICY comments_read ON kanban.comments
    FOR SELECT TO app_anon, app_authenticated, app_admin
    USING (issue_id IN (SELECT id FROM kanban.issues));

-- Anyone signed in can comment, but author_id must match the JWT user.
CREATE POLICY comments_write ON kanban.comments
    FOR INSERT TO app_authenticated, app_admin
    WITH CHECK (author_id = NULLIF(current_setting('request.user_id', true), '')::integer);

CREATE POLICY comments_update_own ON kanban.comments
    FOR UPDATE TO app_authenticated
    USING (author_id = NULLIF(current_setting('request.user_id', true), '')::integer)
    WITH CHECK (author_id = NULLIF(current_setting('request.user_id', true), '')::integer);

CREATE POLICY comments_admin_delete ON kanban.comments
    FOR DELETE TO app_admin USING (true);

-- ── users / time_entries / labels / sprints ────────────────────────────────
CREATE POLICY users_same_org ON kanban.users
    FOR SELECT TO app_authenticated, app_admin
    USING (org_id = (select kanban.current_user_org()));

-- Anon may read user names for display (commenter/assignee/reporter labels) —
-- emails and roles aren't sensitive for the seeded demo data.
GRANT SELECT ON kanban.users TO app_anon;
CREATE POLICY users_anon_read ON kanban.users
    FOR SELECT TO app_anon
    USING (true);

CREATE POLICY time_entries_own ON kanban.time_entries
    FOR ALL TO app_authenticated
    USING (user_id = NULLIF(current_setting('request.user_id', true), '')::integer)
    WITH CHECK (user_id = NULLIF(current_setting('request.user_id', true), '')::integer);

CREATE POLICY time_entries_admin_all ON kanban.time_entries
    FOR ALL TO app_admin USING (true);

-- Labels / issue_labels / sprints are visible to anyone who can see the project.
CREATE POLICY labels_read ON kanban.labels
    FOR SELECT TO app_anon, app_authenticated, app_admin
    USING (project_id IN (SELECT id FROM kanban.projects));

CREATE POLICY issue_labels_read ON kanban.issue_labels
    FOR SELECT TO app_anon, app_authenticated, app_admin
    USING (issue_id IN (SELECT id FROM kanban.issues));

CREATE POLICY sprints_read ON kanban.sprints
    FOR SELECT TO app_anon, app_authenticated, app_admin
    USING (project_id IN (SELECT id FROM kanban.projects));

-- ---------------------------------------------------------------------------
-- Activity feed (jsonb table — the "NoSQL surface in spirit" for this demo)
-- ---------------------------------------------------------------------------
CREATE TABLE kanban.activity (
    id        SERIAL PRIMARY KEY,
    project_id INTEGER REFERENCES kanban.projects(id),
    actor_id  INTEGER REFERENCES kanban.users(id),
    kind      TEXT NOT NULL,        -- 'issue.created', 'comment.posted', 'issue.updated'
    target    JSONB NOT NULL,       -- { issueId, title, ... }
    ts        TIMESTAMPTZ DEFAULT now()
);

GRANT SELECT ON kanban.activity TO app_anon, app_authenticated, app_admin;
GRANT INSERT ON kanban.activity TO app_authenticated, app_admin, app_service;
GRANT USAGE, SELECT ON SEQUENCE kanban.activity_id_seq
    TO app_authenticated, app_admin, app_service;

ALTER TABLE kanban.activity ENABLE ROW LEVEL SECURITY;

-- Anon sees activity on public projects only.
CREATE POLICY activity_anon_read ON kanban.activity
    FOR SELECT TO app_anon
    USING (project_id IN (SELECT id FROM kanban.projects WHERE is_public));

-- Authenticated sees activity on visible projects (own-org + public).
CREATE POLICY activity_authenticated_read ON kanban.activity
    FOR SELECT TO app_authenticated, app_admin
    USING (project_id IN (SELECT id FROM kanban.projects));

-- Triggers populate the feed on issue/comment writes.
CREATE OR REPLACE FUNCTION kanban.activity_on_issue() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
    INSERT INTO kanban.activity(project_id, actor_id, kind, target)
    VALUES (
        NEW.project_id,
        NULLIF(current_setting('request.user_id', true), '')::integer,
        CASE WHEN TG_OP = 'INSERT' THEN 'issue.created' ELSE 'issue.updated' END,
        jsonb_build_object(
            'issueId', NEW.id, 'title', NEW.title, 'status', NEW.status,
            'priority', NEW.priority
        )
    );
    RETURN NEW;
END $$;

CREATE TRIGGER issue_activity AFTER INSERT OR UPDATE ON kanban.issues
    FOR EACH ROW EXECUTE FUNCTION kanban.activity_on_issue();

CREATE OR REPLACE FUNCTION kanban.activity_on_comment() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
    INSERT INTO kanban.activity(project_id, actor_id, kind, target)
    SELECT i.project_id,
           NEW.author_id,
           'comment.posted',
           jsonb_build_object('issueId', NEW.issue_id, 'commentId', NEW.id, 'snippet', LEFT(NEW.body, 80))
    FROM kanban.issues i WHERE i.id = NEW.issue_id;
    RETURN NEW;
END $$;

CREATE TRIGGER comment_activity AFTER INSERT ON kanban.comments
    FOR EACH ROW EXECUTE FUNCTION kanban.activity_on_comment();

-- ---------------------------------------------------------------------------
-- Seed activity rows so the demo isn't empty before any user-driven writes.
-- ---------------------------------------------------------------------------
INSERT INTO kanban.activity (project_id, actor_id, kind, target)
SELECT i.project_id, i.reporter_id, 'issue.created',
       jsonb_build_object('issueId', i.id, 'title', i.title, 'status', i.status, 'priority', i.priority)
FROM kanban.issues i;

INSERT INTO kanban.activity (project_id, actor_id, kind, target)
SELECT i.project_id, c.author_id, 'comment.posted',
       jsonb_build_object('issueId', c.issue_id, 'commentId', c.id, 'snippet', LEFT(c.body, 80))
FROM kanban.comments c JOIN kanban.issues i ON i.id = c.issue_id;
