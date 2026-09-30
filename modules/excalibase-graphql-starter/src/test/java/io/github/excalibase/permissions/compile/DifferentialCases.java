package io.github.excalibase.permissions.compile;

import java.util.ArrayList;
import java.util.List;

import static io.github.excalibase.permissions.compile.FixtureSchema.MEMBERS;
import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.ORGS;
import static io.github.excalibase.permissions.compile.FixtureSchema.U1;

/**
 * Each case: a Hasura expression and the policy a person would hand-write for native Postgres.
 * Session variables reach the native policy through {@code current_setting('x.<name>', true)}.
 */
final class DifferentialCases {

    /** How far the in-memory matcher can decide the case without the database. */
    enum Judge { MEMORY, DATABASE, MIXED }

    record Case(String table, String expression, String nativeSql, Judge judge) {
        @Override
        public String toString() {
            return table + " " + expression;
        }
    }

    private static final String UID = var("user_id", "uuid");
    private static final String ORG_MEMBER = "EXISTS (SELECT 1 FROM public.orgs o WHERE o.id = notes.org_id"
            + " AND EXISTS (SELECT 1 FROM public.members m WHERE m.org_id = o.id AND m.user_id = " + UID + "))";

    private DifferentialCases() {
    }

    static String var(String name, String type) {
        return "current_setting('x." + name + "', true)::" + type;
    }

    static List<Case> all() {
        List<Case> cases = new ArrayList<>();
        cases.addAll(integersAndNumbers());
        cases.addAll(text());
        cases.addAll(typed());
        cases.addAll(sessionVariables());
        cases.addAll(logic());
        cases.addAll(relationships());
        return List.copyOf(cases);
    }

    private static List<Case> integersAndNumbers() {
        return List.of(
                mem("{\"id\": {\"_eq\": 1}}", "id = 1"),
                mem("{\"id\": {\"_neq\": 1}}", "id <> 1"),
                mem("{\"id\": {\"_gt\": 3}}", "id > 3"),
                mem("{\"id\": {\"_gte\": 3}}", "id >= 3"),
                mem("{\"id\": {\"_lt\": 3}}", "id < 3"),
                mem("{\"id\": {\"_lte\": 3}}", "id <= 3"),
                mem("{\"id\": {\"_in\": [1, 3, 5]}}", "id IN (1, 3, 5)"),
                mem("{\"id\": {\"_nin\": [1, 3, 5]}}", "id NOT IN (1, 3, 5)"),
                mem("{\"id\": {\"_in\": []}}", "false"),
                mem("{\"id\": {\"_nin\": []}}", "true"),
                mem("{\"priority\": {\"_neq\": 2}}", "priority <> 2"),
                mem("{\"amount\": {\"_eq\": 10.5}}", "amount = 10.5"),
                mem("{\"amount\": {\"_gt\": 0}}", "amount > 0"),
                mem("{\"amount\": {\"_lte\": 0}}", "amount <= 0"),
                mem("{\"amount\": {\"_neq\": 0}}", "amount <> 0"),
                mem("{\"amount\": {\"_in\": [0, 100]}}", "amount IN (0, 100)"),
                mem("{\"amount\": {\"_nin\": [0, 100]}}", "amount NOT IN (0, 100)"),
                mem("{\"amount\": {\"_gte\": \"-5\"}}", "amount >= -5"),
                mem("{\"views\": {\"_gt\": 1000000000000}}", "views > 1000000000000"),
                mem("{\"views\": {\"_lte\": 7}}", "views <= 7"),
                mem("{\"score\": {\"_gt\": 1}}", "score > 1"),
                mem("{\"score\": {\"_eq\": 1.5}}", "score = 1.5"));
    }

    private static List<Case> text() {
        return List.of(
                mem("{\"status\": {\"_eq\": \"open\"}}", "status = 'open'"),
                mem("{\"status\": {\"_neq\": \"open\"}}", "status <> 'open'"),
                mem("{\"status\": {\"_in\": [\"open\", \"draft\"]}}", "status IN ('open', 'draft')"),
                mem("{\"status\": {\"_nin\": [\"open\", \"draft\"]}}", "status NOT IN ('open', 'draft')"),
                mem("{\"status\": {\"_is_null\": true}}", "status IS NULL"),
                mem("{\"status\": {\"_is_null\": false}}", "status IS NOT NULL"),
                db("{\"status\": {\"_gt\": \"closed\"}}", "status > 'closed'"),
                db("{\"title\": {\"_lte\": \"beta\"}}", "title <= 'beta'"),
                mem("{\"title\": {\"_like\": \"A%\"}}", "title LIKE 'A%'"),
                mem("{\"title\": {\"_nlike\": \"A%\"}}", "title NOT LIKE 'A%'"),
                mem("{\"title\": {\"_like\": \"%a%\"}}", "title LIKE '%a%'"),
                mem("{\"title\": {\"_like\": \"_eta\"}}", "title LIKE '_eta'"),
                mem("{\"title\": {\"_like\": \"a\\\\%b\"}}", "title LIKE 'a\\%b'"),
                mem("{\"title\": {\"_like\": \"Gamma\\\\_x\"}}", "title LIKE 'Gamma\\_x'"),
                mem("{\"title\": {\"_nlike\": \"%\\\\_%\"}}", "title NOT LIKE '%\\_%'"),
                mem("{\"title\": {\"_like\": \"%\"}}", "title LIKE '%'"));
    }

    private static List<Case> typed() {
        return List.of(
                mem("{\"created_at\": {\"_gt\": \"2026-01-01T10:00:00Z\"}}", "created_at > '2026-01-01T10:00:00Z'::timestamptz"),
                mem("{\"created_at\": {\"_lte\": \"2026-01-01 10:00:00+00\"}}", "created_at <= '2026-01-01 10:00:00+00'::timestamptz"),
                mem("{\"created_at\": {\"_eq\": \"2026-03-01T10:30:00Z\"}}", "created_at = '2026-03-01T10:30:00Z'::timestamptz"),
                mem("{\"created_at\": {\"_neq\": \"2026-01-01T10:00:00+00:00\"}}", "created_at <> '2026-01-01T10:00:00Z'::timestamptz"),
                mem("{\"local_at\": {\"_gte\": \"2026-01-01\"}}", "local_at >= '2026-01-01'::timestamp"),
                mem("{\"due\": {\"_lt\": \"2026-06-01\"}}", "due < '2026-06-01'::date"),
                mem("{\"due\": {\"_is_null\": false}}", "due IS NOT NULL"),
                mem("{\"pinned\": {\"_eq\": true}}", "pinned = true"),
                mem("{\"pinned\": {\"_neq\": true}}", "pinned <> true"),
                mem("{\"pinned\": {\"_in\": [true]}}", "pinned IN (true)"),
                mem("{\"owner_id\": {\"_eq\": \"" + U1 + "\"}}", "owner_id = '" + U1 + "'::uuid"),
                mem("{\"owner_id\": {\"_gt\": \"7fffffff-ffff-ffff-ffff-ffffffffffff\"}}",
                        "owner_id > '7fffffff-ffff-ffff-ffff-ffffffffffff'::uuid"),
                mem("{\"owner_id\": {\"_is_null\": true}}", "owner_id IS NULL"),
                mem("{\"mood\": {\"_eq\": \"ok\"}}", "mood = 'ok'"),
                mem("{\"mood\": {\"_in\": [\"sad\", \"happy\"]}}", "mood IN ('sad', 'happy')"),
                db("{\"mood\": {\"_gt\": \"sad\"}}", "mood > 'sad'"),
                db("{\"ip\": {\"_eq\": \"10.0.0.1\"}}", "ip = '10.0.0.1'::inet"),
                mem("{\"ip\": {\"_is_null\": true}}", "ip IS NULL"));
    }

    private static List<Case> sessionVariables() {
        String uids = var("user_ids", "uuid[]");
        String empty = var("empty_ids", "uuid[]");
        return List.of(
                mem("{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}", "owner_id = " + UID),
                mem("{\"owner_id\": {\"_neq\": \"x-excalibase-user-id\"}}", "owner_id <> " + UID),
                mem("{\"owner_id\": {\"_in\": \"X-Excalibase-User-Ids\"}}", "owner_id = ANY (" + uids + ")"),
                mem("{\"owner_id\": {\"_nin\": \"X-Excalibase-User-Ids\"}}", "owner_id <> ALL (" + uids + ")"),
                mem("{\"owner_id\": {\"_in\": \"X-Excalibase-Empty-Ids\"}}", "owner_id = ANY (" + empty + ")"),
                mem("{\"owner_id\": {\"_nin\": \"X-Excalibase-Empty-Ids\"}}", "owner_id <> ALL (" + empty + ")"),
                mem("{\"owner_id\": {\"_in\": \"X-Excalibase-User-Id\"}}", "owner_id IN (" + UID + ")"),
                mem("{\"org_id\": {\"_eq\": \"X-Excalibase-Org-Id\"}}", "org_id = " + var("org_id", "int")),
                mem("{\"org_id\": {\"_gt\": \"X-Excalibase-Org-Id\"}}", "org_id > " + var("org_id", "int")),
                mem("{\"org_id\": {\"_in\": \"X-Excalibase-Org-Ids\"}}", "org_id = ANY (" + var("org_ids", "int[]") + ")"),
                mem("{\"org_id\": {\"_nin\": \"X-Excalibase-Org-Ids\"}}", "org_id <> ALL (" + var("org_ids", "int[]") + ")"),
                mem("{\"status\": {\"_eq\": \"X-Excalibase-Team\"}}", "status = " + var("team", "text")),
                mem("{\"status\": {\"_neq\": \"X-Excalibase-Team\"}}", "status <> " + var("team", "text")),
                mem("{\"title\": {\"_like\": \"X-Excalibase-Title-Pattern\"}}", "title LIKE " + var("title_pattern", "text")),
                mem("{\"due\": {\"_eq\": \"X-Excalibase-Due\"}}", "due = " + var("due", "date")));
    }

    private static List<Case> logic() {
        String open = "{\"status\": {\"_eq\": \"open\"}}";
        String mine = "{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}";
        return List.of(
                mem("{\"_and\": [" + open + ", {\"amount\": {\"_gt\": 0}}]}", "status = 'open' AND amount > 0"),
                mem("{\"_or\": [" + open + ", {\"amount\": {\"_gt\": 50}}]}", "status = 'open' OR amount > 50"),
                mem("{\"_not\": " + open + "}", "NOT (status = 'open')"),
                mem("{\"_not\": {\"_not\": " + open + "}}", "NOT (NOT (status = 'open'))"),
                mem("{\"_not\": " + mine + "}", "NOT (owner_id = " + UID + ")"),
                mem("{\"_or\": [{\"_not\": " + open + "}, {\"amount\": {\"_is_null\": true}}]}",
                        "NOT (status = 'open') OR amount IS NULL"),
                mem("{\"status\": {\"_eq\": \"open\"}, \"org_id\": {\"_eq\": 2}}", "status = 'open' AND org_id = 2"),
                mem("{\"_and\": []}", "true"),
                mem("{\"_or\": []}", "false"),
                mem("{}", "true"),
                mem("{\"_not\": {\"_or\": [" + open + ", " + mine + "]}}", "NOT (status = 'open' OR owner_id = " + UID + ")"),
                mem("{\"id\": {\"_gt\": 1, \"_lt\": 5}}", "id > 1 AND id < 5"),
                mem("{\"_not\": {\"_and\": [{\"amount\": {\"_gt\": 0}}, {\"status\": {\"_neq\": \"open\"}}]}}",
                        "NOT (amount > 0 AND status <> 'open')"),
                mem("{\"_or\": [{\"amount\": {\"_gt\": 1000}}, {\"status\": {\"_eq\": \"none\"}}]}",
                        "amount > 1000 OR status = 'none'"));
    }

    private static List<Case> relationships() {
        return List.of(
                db("{\"publicOrgId\": {\"name\": {\"_eq\": \"acme\"}}}",
                        "EXISTS (SELECT 1 FROM public.orgs o WHERE o.id = notes.org_id AND o.name = 'acme')"),
                db("{\"publicOrgId\": {}}", "EXISTS (SELECT 1 FROM public.orgs o WHERE o.id = notes.org_id)"),
                db("{\"publicMembers\": {\"role\": {\"_eq\": \"admin\"}}}", "EXISTS (SELECT 1 FROM public.members m"
                        + " WHERE m.org_id = notes.org_id AND m.user_id = notes.owner_id AND m.role = 'admin')"),
                db("{\"publicMembers\": {}}", "EXISTS (SELECT 1 FROM public.members m"
                        + " WHERE m.org_id = notes.org_id AND m.user_id = notes.owner_id)"),
                db("{\"publicOrgId\": {\"publicMembers\": {\"user_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}}}", ORG_MEMBER),
                db("{\"_not\": {\"publicOrgId\": {\"name\": {\"_eq\": \"acme\"}}}}",
                        "NOT EXISTS (SELECT 1 FROM public.orgs o WHERE o.id = notes.org_id AND o.name = 'acme')"),
                mixed("{\"_or\": [{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}},"
                        + " {\"publicOrgId\": {\"publicMembers\": {\"user_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}}}]}",
                        "owner_id = " + UID + " OR " + ORG_MEMBER),
                db("{\"_exists\": {\"_table\": \"public.members\", \"_where\": {\"user_id\": {\"_eq\": \"X-Excalibase-User-Id\"},"
                        + " \"role\": {\"_eq\": \"admin\"}}}}",
                        "EXISTS (SELECT 1 FROM public.members m WHERE m.user_id = " + UID + " AND m.role = 'admin')"),
                db("{\"_exists\": {\"_table\": \"public.members\", \"_where\": {\"role\": {\"_eq\": \"owner\"}}}}",
                        "EXISTS (SELECT 1 FROM public.members m WHERE m.role = 'owner')"),
                mixed("{\"_and\": [{\"status\": {\"_eq\": \"open\"}}, {\"_exists\": {\"_table\": \"public.members\","
                        + " \"_where\": {\"role\": {\"_eq\": \"X-Excalibase-Role-Name\"}}}}]}",
                        "status = 'open' AND EXISTS (SELECT 1 FROM public.members m WHERE m.role = " + var("role_name", "text") + ")"),
                on(ORGS, Judge.DATABASE, "{\"publicMembers\": {\"user_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}}",
                        "EXISTS (SELECT 1 FROM public.members m WHERE m.org_id = orgs.id AND m.user_id = " + UID + ")"),
                on(ORGS, Judge.DATABASE, "{\"publicNotes\": {\"amount\": {\"_gt\": 50}}}",
                        "EXISTS (SELECT 1 FROM public.notes n WHERE n.org_id = orgs.id AND n.amount > 50)"),
                on(ORGS, Judge.MEMORY, "{\"name\": {\"_neq\": \"acme\"}}", "name <> 'acme'"),
                on(MEMBERS, Judge.DATABASE, "{\"publicNotes\": {\"status\": {\"_eq\": \"open\"}}}",
                        "EXISTS (SELECT 1 FROM public.notes n WHERE n.org_id = members.org_id"
                                + " AND n.owner_id = members.user_id AND n.status = 'open')"),
                on(MEMBERS, Judge.DATABASE, "{\"publicOrgId\": {\"name\": {\"_is_null\": true}}}",
                        "EXISTS (SELECT 1 FROM public.orgs o WHERE o.id = members.org_id AND o.name IS NULL)"),
                on(MEMBERS, Judge.MIXED, "{\"_or\": [{\"role\": {\"_eq\": \"admin\"}}, {\"publicNotes\": {}}]}",
                        "role = 'admin' OR EXISTS (SELECT 1 FROM public.notes n WHERE n.org_id = members.org_id"
                                + " AND n.owner_id = members.user_id)"));
    }

    private static Case mem(String expression, String nativeSql) {
        return on(NOTES, Judge.MEMORY, expression, nativeSql);
    }

    private static Case db(String expression, String nativeSql) {
        return on(NOTES, Judge.DATABASE, expression, nativeSql);
    }

    private static Case mixed(String expression, String nativeSql) {
        return on(NOTES, Judge.MIXED, expression, nativeSql);
    }

    private static Case on(String table, Judge judge, String expression, String nativeSql) {
        return new Case(table, expression, nativeSql, judge);
    }
}
