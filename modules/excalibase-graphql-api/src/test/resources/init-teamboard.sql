-- Fixture shaped like the prod user test app (EXC-641): a workspace is readable through its membership rows, and
-- the owner's membership is added by a trigger in the same statement that inserts the workspace.

CREATE TABLE workspaces (
    id       BIGSERIAL PRIMARY KEY,
    name     TEXT NOT NULL,
    owner_id TEXT NOT NULL
);

CREATE TABLE workspace_members (
    workspace_id BIGINT NOT NULL REFERENCES workspaces (id),
    user_id      TEXT NOT NULL,
    PRIMARY KEY (workspace_id, user_id)
);

CREATE FUNCTION add_owner_membership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO workspace_members (workspace_id, user_id) VALUES (NEW.id, NEW.owner_id);
    RETURN NEW;
END
$$;

CREATE TRIGGER workspaces_owner_membership AFTER INSERT ON workspaces
    FOR EACH ROW EXECUTE FUNCTION add_owner_membership();

CREATE TABLE projects (
    id   BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE tasks (
    id         BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects (id),
    title      TEXT NOT NULL,
    points     INT NOT NULL,
    hidden     BOOLEAN NOT NULL DEFAULT false
);

INSERT INTO projects (name) VALUES ('alpha'), ('beta');
INSERT INTO tasks (project_id, title, points, hidden) VALUES
    (1, 'a1', 1, false), (1, 'a2', 2, false), (1, 'a3', 5, true),
    (2, 'b1', 3, false);
