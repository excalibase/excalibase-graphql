-- Writes whose connection is killed by the database itself: inside the statement, and at COMMIT.
CREATE FUNCTION kill_own_connection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_terminate_backend(pg_backend_pid());
    RETURN NEW;
END $$;

CREATE TABLE in_statement (id SERIAL PRIMARY KEY, note TEXT);
CREATE TRIGGER kill_in_statement BEFORE INSERT ON in_statement
    FOR EACH ROW EXECUTE FUNCTION kill_own_connection();

CREATE TABLE at_commit (id SERIAL PRIMARY KEY, note TEXT);
CREATE CONSTRAINT TRIGGER kill_at_commit AFTER INSERT ON at_commit
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION kill_own_connection();

-- A deferred constraint that fails at COMMIT.
CREATE TABLE deferred_check (id SERIAL PRIMARY KEY, qty INT);
ALTER TABLE deferred_check ADD CONSTRAINT qty_positive CHECK (qty > 0) NOT VALID;
CREATE TABLE deferred_child (id INT PRIMARY KEY, parent_id INT,
    CONSTRAINT deferred_child_parent FOREIGN KEY (parent_id) REFERENCES deferred_check (id)
        DEFERRABLE INITIALLY DEFERRED);
