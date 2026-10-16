package io.github.excalibase.permissions.compile;

import io.github.excalibase.access.ChangeFilter;
import io.github.excalibase.access.ProbeRunner;
import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.compile.DifferentialCases.Case;
import io.github.excalibase.permissions.compile.MysqlDifferentialFixture.Collation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static io.github.excalibase.permissions.compile.PermissionDialect.MYSQL;
import static io.github.excalibase.permissions.compile.PermissionExpressionDifferentialTest.VARIABLES;
import static io.github.excalibase.permissions.compile.PermissionExpressionDifferentialTest.nativeRows;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL has no native oracle, so native Postgres row security is it: on identical fixtures (MySQL text
 * under {@code utf8mb4_bin}, comparing like Postgres), every case's MySQL SQL keeps exactly the rows the
 * hand-written Postgres policy keeps: as a read, as the WHERE of a DELETE, and as realtime decides them.
 */
class MysqlPermissionDifferentialTest {

    private static MysqlDifferentialFixture mysql;

    @BeforeAll
    static void start() {
        PermissionExpressionDifferentialTest.connect();
        mysql = MysqlDifferentialFixture.of(Collation.BIN);
    }

    static List<Case> cases() {
        return DifferentialCases.all();
    }

    @Test
    void everyCaseRuns() {
        assertThat(cases()).hasSizeGreaterThanOrEqualTo(80);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void compiledFilter_keepsTheRowsNativePostgresKeeps(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());

        assertThat(mysql.rowsKept(testCase.table(), compile(testCase, false))).isEqualTo(oracle);
    }

    /** Inside a DELETE, a filter reading its own table goes through a derived table (MySQL error 1093). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void writeFilter_reachesTheRowsNativePostgresKeeps(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());

        assertThat(mysql.rowsDeleted(testCase.table(), compile(testCase, true))).isEqualTo(oracle);
    }

    /** A row that now exists is judged in memory or by its key; a deleted one by its image alone. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void realtime_deliversTheRowsNativePostgresKeeps(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());
        ChangeFilter subscription = mysql.subscription(testCase.table(), parse(testCase.expression()));
        ProbeRunner probes = mysql.probes();

        for (Map<String, Object> row : mysql.allRows(testCase.table())) {
            String key = DifferentialFixture.key(testCase.table(), row);
            assertThat(subscription.render("INSERT", row, probes)).as("insert of %s", key)
                    .isEqualTo(oracle.contains(key) ? Optional.of(row) : Optional.empty());
            assertThat(subscription.render("DELETE", row, probes).isPresent()).as("delete of %s", key)
                    .isEqualTo(oracle.contains(key));
        }
    }

    private static SqlFragment compile(Case testCase, boolean write) {
        BoolExp expression = parse(testCase.expression());
        SessionBinding binding = new SessionBinding(VARIABLES);
        ParamNamer namer = new ParamNamer("perm");
        return write
                ? FilterCompiler.compileForWrite(MYSQL, expression, testCase.table(), "t", mysql.schema(), binding, namer)
                : FilterCompiler.compile(MYSQL, expression, testCase.table(), "t", mysql.schema(), binding, namer);
    }
}
