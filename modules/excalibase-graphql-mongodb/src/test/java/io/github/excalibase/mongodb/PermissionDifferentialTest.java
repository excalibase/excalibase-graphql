package io.github.excalibase.mongodb;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.RowMatcher;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.rls.UserContext;
import org.bson.Document;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same rules must keep the same rows on every path. Native Postgres RLS is the oracle: for every case,
 * the pipeline filter run on a real MongoDB-compatible database, the in-memory matcher and Postgres itself
 * must show a caller exactly the same documents, for a signed-in caller and for an anonymous one.
 */
abstract class PermissionDifferentialTest {

    private static final String UID = "NULLIF(current_setting('rls.uid', true), '')";
    private static final String COLLECTION = "null_t";

    abstract Backend backend();

    /** A set of policies and the Postgres policies that mean the same thing. */
    record Case(String label, List<Policy> policies, String permissive, String restrictive) {
        @Override public String toString() { return label; }
    }

    private static Rule text(String field, RuleOperator op, String value) {
        return new Rule(field, FieldType.STRING, op, value);
    }

    private static Rule number(RuleOperator op, String value) {
        return new Rule("n", FieldType.INTEGER, op, value);
    }

    private static Policy policy(PolicyEffect effect, LogicOperator logic, Rule... rules) {
        return new Policy("p-" + effect, "p", COLLECTION, effect, Operation.ALL, logic, 0, true, List.of(rules),
                List.of(Assignment.all()));
    }

    private static Case single(Rule rule, String predicate) {
        String label = rule.field() + " " + rule.operator() + " " + rule.value();
        return new Case("ALLOW " + label, List.of(policy(PolicyEffect.ALLOW, LogicOperator.AND, rule)), predicate, null);
    }

    private static Case denied(Rule rule, String predicate) {
        String label = rule.field() + " " + rule.operator() + " " + rule.value();
        return new Case("DENY " + label, List.of(policy(PolicyEffect.ALLOW, LogicOperator.AND,
                text("id", RuleOperator.IS_NOT_NULL, null)), policy(PolicyEffect.DENY, LogicOperator.AND, rule)),
                "true", "NOT (" + predicate + ")");
    }

    static Stream<Case> cases() {
        List<Object[]> rules = List.of(
                new Object[] {text("owner_txt", RuleOperator.EQ, "{{currentUserId}}"), "owner_txt = " + UID},
                new Object[] {text("owner_txt", RuleOperator.NEQ, "{{currentUserId}}"), "owner_txt <> " + UID},
                new Object[] {text("owner_txt", RuleOperator.IN, "{{currentUserId}}"), "owner_txt IN (" + UID + ")"},
                new Object[] {text("owner_txt", RuleOperator.NOT_IN, "{{currentUserId}}"), "owner_txt NOT IN (" + UID + ")"},
                new Object[] {text("owner_txt", RuleOperator.LIKE, "{{currentUserId}}"), "owner_txt LIKE " + UID},
                new Object[] {text("owner_txt", RuleOperator.NOT_LIKE, "{{currentUserId}}"), "owner_txt NOT LIKE " + UID},
                new Object[] {text("status", RuleOperator.EQ, "active"), "status = 'active'"},
                new Object[] {text("status", RuleOperator.NEQ, "archived"), "status <> 'archived'"},
                new Object[] {text("status", RuleOperator.IN, "active,archived"), "status IN ('active', 'archived')"},
                new Object[] {text("status", RuleOperator.NOT_IN, "archived,deleted"), "status NOT IN ('archived', 'deleted')"},
                new Object[] {text("status", RuleOperator.LIKE, "act%"), "status LIKE 'act%'"},
                new Object[] {text("status", RuleOperator.LIKE, "a_tive"), "status LIKE 'a_tive'"},
                new Object[] {text("status", RuleOperator.LIKE, "a.tive"), "status LIKE 'a.tive'"},
                new Object[] {text("status", RuleOperator.LIKE, "active"), "status LIKE 'active'"},
                new Object[] {text("status", RuleOperator.NOT_LIKE, "arch%"), "status NOT LIKE 'arch%'"},
                new Object[] {text("status", RuleOperator.IS_NULL, null), "status IS NULL"},
                new Object[] {text("status", RuleOperator.IS_NOT_NULL, null), "status IS NOT NULL"},
                new Object[] {number(RuleOperator.EQ, "5"), "n = 5"},
                new Object[] {number(RuleOperator.NEQ, "5"), "n <> 5"},
                new Object[] {number(RuleOperator.GT, "4"), "n > 4"},
                new Object[] {number(RuleOperator.GTE, "5"), "n >= 5"},
                new Object[] {number(RuleOperator.LT, "7"), "n < 7"},
                new Object[] {number(RuleOperator.LTE, "5"), "n <= 5"},
                new Object[] {number(RuleOperator.IN, "1,10"), "n IN (1, 10)"},
                new Object[] {number(RuleOperator.NOT_IN, "1,10"), "n NOT IN (1, 10)"},
                new Object[] {number(RuleOperator.IS_NULL, null), "n IS NULL"});
        List<Case> cases = new ArrayList<>();
        for (Object[] pair : rules) {
            cases.add(single((Rule) pair[0], (String) pair[1]));
            cases.add(denied((Rule) pair[0], (String) pair[1]));
        }
        cases.add(new Case("AND of two rules", List.of(policy(PolicyEffect.ALLOW, LogicOperator.AND,
                text("owner_txt", RuleOperator.EQ, "{{currentUserId}}"), text("status", RuleOperator.EQ, "active"))),
                "(owner_txt = " + UID + " AND status = 'active')", null));
        cases.add(new Case("OR of two rules", List.of(policy(PolicyEffect.ALLOW, LogicOperator.OR,
                text("status", RuleOperator.EQ, "active"), number(RuleOperator.GT, "6"))),
                "(status = 'active' OR n > 6)", null));
        cases.add(new Case("two ALLOW policies", List.of(
                policy(PolicyEffect.ALLOW, LogicOperator.AND, text("owner_txt", RuleOperator.EQ, "{{currentUserId}}")),
                policy(PolicyEffect.ALLOW, LogicOperator.AND, text("status", RuleOperator.EQ, "archived"))),
                "owner_txt = " + UID + " OR status = 'archived'", null));
        cases.add(new Case("ALLOW minus a compound DENY", List.of(
                policy(PolicyEffect.ALLOW, LogicOperator.AND, text("id", RuleOperator.IS_NOT_NULL, null)),
                policy(PolicyEffect.DENY, LogicOperator.OR, text("status", RuleOperator.EQ, "active"), number(RuleOperator.GT, "6"))),
                "true", "NOT (status = 'active' OR n > 6)"));
        cases.add(new Case("two DENY policies", List.of(
                policy(PolicyEffect.ALLOW, LogicOperator.AND, text("id", RuleOperator.IS_NOT_NULL, null)),
                policy(PolicyEffect.DENY, LogicOperator.AND, text("status", RuleOperator.EQ, "active")),
                policy(PolicyEffect.DENY, LogicOperator.AND, number(RuleOperator.LT, "4"))),
                "true", "NOT (status = 'active' OR n < 4)"));
        return cases.stream();
    }

    static Stream<Arguments> caseByCaller() {
        return cases().flatMap(c -> Stream.of("1", "2", "").map(uid -> Arguments.of(c, uid)));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("caseByCaller")
    void mongoKeepsTheRowsPostgresKeeps(Case testCase, String uid) throws Exception {
        UserContext caller = caller(uid.isEmpty() ? null : uid);
        List<Integer> oracle = PostgresOracle.INSTANCE.visibleIds(testCase.permissive(), testCase.restrictive(), uid);

        assertThat(mongoIds(testCase.policies(), caller)).as("pipeline vs Postgres").isEqualTo(oracle);
        // RowMatcher's LIKE turns % into ".*", which does not cross a newline, so rows 7 and 8 are left to the
        // two SQL-faithful paths (a divergence of rls-core's realtime matcher, noted in the ADR)
        assertThat(inMemoryIds(testCase.policies(), caller)).as("in-memory matcher vs Postgres")
                .isEqualTo(oracle.stream().filter(id -> id < 7).toList());
    }

    private List<Integer> mongoIds(List<Policy> policies, UserContext caller) {
        Catalog catalog = Catalog.of(new CollectionDef(COLLECTION, FieldKind.INT, fields()));
        RowSecurity security = new RowSecurity(catalog, policies);
        Backend backend = backend();
        String database = "diff_" + backend.name();
        backend.seed(database, COLLECTION, documents());
        try (PipelineRunner runner = new DriverPipelineRunner(backend.uri(), database, 10_000, backend.sslContext())) {
            Cond filter = security.filter(COLLECTION, caller, Operation.SELECT);
            return runner.run(COLLECTION, List.of(new Document("$match", filter.doc()),
                            new Document("$sort", new Document("_id", 1)), new Document("$project", new Document("_id", 1))))
                    .stream().map(doc -> doc.getInteger("_id")).toList();
        }
    }

    private static Map<String, FieldKind> fields() {
        Map<String, FieldKind> fields = new LinkedHashMap<>();
        fields.put("owner_txt", FieldKind.STRING);
        fields.put("status", FieldKind.STRING);
        fields.put("n", FieldKind.INT);
        return fields;
    }

    private static List<Document> documents() {
        List<Document> documents = new ArrayList<>();
        for (PostgresOracle.Row row : PostgresOracle.ROWS) {
            Document doc = new Document("_id", row.id());
            put(doc, row.id(), "owner_txt", row.owner());
            put(doc, row.id(), "status", row.status());
            put(doc, row.id(), "n", row.n());
            documents.add(doc);
        }
        return documents;
    }

    /** SQL's NULL is both a stored null and a missing field in a document; odd ids use the first, even ids the second. */
    private static void put(Document doc, int id, String field, Object value) {
        if (value != null) {
            doc.append(field, value);
        } else if (id % 2 == 1) {
            doc.append(field, null);
        }
    }

    private static List<Integer> inMemoryIds(List<Policy> policies, UserContext caller) throws Exception {
        RowMatcher matcher = new RowMatcher(policies);
        List<Integer> kept = new ArrayList<>();
        for (Map<String, Object> row : PostgresOracle.INSTANCE.rows()) {
            if ((Integer) row.get("id") >= 7) {
                continue;
            }
            Map<String, Object> asRule = new LinkedHashMap<>(row);
            if (matcher.matches(COLLECTION, asRule, caller, Operation.SELECT)) {
                kept.add((Integer) row.get("id"));
            }
        }
        return kept;
    }

    private static UserContext caller(String uid) {
        return Fixtures.caller(uid);
    }

}
