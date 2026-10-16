package io.github.excalibase.permissions.compile;

import io.github.excalibase.access.ChangeFilter;
import io.github.excalibase.access.ProbeRunner;
import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.compile.DifferentialCases.Case;
import io.github.excalibase.permissions.compile.DifferentialCases.Judge;
import io.github.excalibase.permissions.compile.MysqlDifferentialFixture.Collation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static io.github.excalibase.permissions.compile.PermissionDialect.MYSQL;
import static io.github.excalibase.permissions.compile.PermissionExpressionDifferentialTest.VARIABLES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL projects keep MySQL's default collation ({@code utf8mb4_0900_ai_ci}), so {@code _eq: "alpha"}
 * keeps "Alpha". Postgres has no answer for that; what must hold is that realtime delivers exactly the
 * rows the SQL keeps, which is why text is judged by the database, never in memory, on MySQL.
 */
class MysqlCollationAgreementTest {

    private static MysqlDifferentialFixture mysql;

    @BeforeAll
    static void start() {
        PermissionExpressionDifferentialTest.connect();
        mysql = MysqlDifferentialFixture.of(Collation.AI_CI);
    }

    static List<Case> cases() {
        List<Case> cases = new ArrayList<>(DifferentialCases.all());
        for (String expression : List.of(
                "{\"title\": {\"_eq\": \"alpha\"}}",
                "{\"title\": {\"_neq\": \"BETA\"}}",
                "{\"title\": {\"_like\": \"a%\"}}",
                "{\"title\": {\"_nlike\": \"%A%\"}}",
                "{\"_not\": {\"title\": {\"_eq\": \"álpha\"}}}",
                "{\"status\": {\"_in\": [\"OPEN\", \"Draft\"]}}",
                "{\"title\": {\"_gt\": \"ALPHA\"}}",
                "{\"publicOrgId\": {\"name\": {\"_eq\": \"ACME\"}}}")) {
            cases.add(new Case(NOTES, expression, null, Judge.DATABASE));
        }
        return cases;
    }

    @Test
    void theCollationDecides_andRealtimeFollowsIt() {
        assertThat(rowsKept(new Case(NOTES, "{\"title\": {\"_eq\": \"alpha\"}}", null, Judge.DATABASE)))
                .containsExactly("1", "7");
        assertThat(rowsKept(new Case(NOTES, "{\"_not\": {\"title\": {\"_eq\": \"álpha\"}}}", null, Judge.DATABASE)))
                .containsExactly("2", "3", "5", "6", "8");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void realtime_deliversTheRowsTheSqlKeeps(Case testCase) {
        TreeSet<String> kept = rowsKept(testCase);
        ChangeFilter subscription = mysql.subscription(testCase.table(), parse(testCase.expression()));
        ProbeRunner probes = mysql.probes();

        for (Map<String, Object> row : mysql.allRows(testCase.table())) {
            String key = DifferentialFixture.key(testCase.table(), row);
            assertThat(subscription.render("INSERT", row, probes).isPresent()).as("insert of %s", key)
                    .isEqualTo(kept.contains(key));
            assertThat(subscription.render("DELETE", row, probes).isPresent()).as("delete of %s", key)
                    .isEqualTo(kept.contains(key));
        }
    }

    private static TreeSet<String> rowsKept(Case testCase) {
        BoolExp expression = parse(testCase.expression());
        return mysql.rowsKept(testCase.table(), FilterCompiler.compile(MYSQL, expression, testCase.table(), "t",
                mysql.schema(), new SessionBinding(VARIABLES), new ParamNamer("perm")));
    }
}
