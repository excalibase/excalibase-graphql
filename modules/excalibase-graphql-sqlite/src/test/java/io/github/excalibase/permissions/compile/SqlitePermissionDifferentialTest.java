package io.github.excalibase.permissions.compile;

import io.github.excalibase.access.RequestGuard;
import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.compile.DifferentialCases.Case;
import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.List;
import java.util.TreeSet;

import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static io.github.excalibase.permissions.compile.PermissionDialect.SQLITE;
import static io.github.excalibase.permissions.compile.PermissionExpressionDifferentialTest.VARIABLES;
import static io.github.excalibase.permissions.compile.PermissionExpressionDifferentialTest.nativeRows;
import static io.github.excalibase.permissions.compile.PermissionExpressionDifferentialTest.nativeWritesAccepted;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLite has no row security of its own, so native Postgres row security is the oracle: on identical rows, every
 * case's SQLite SQL keeps exactly the rows the hand-written Postgres policy keeps: as a read, as the WHERE of a
 * DELETE, and as an INSERT or UPDATE check. (Realtime is refused on SQLite, so no change-feed case.)
 */
class SqlitePermissionDifferentialTest {

    private static SqliteDifferentialFixture sqlite;

    @BeforeAll
    static void start() {
        PermissionExpressionDifferentialTest.connect();
        sqlite = SqliteDifferentialFixture.get();
    }

    /**
     * Every case but the ones about an enum's declaration order: SQLite has no enum type, so such a column is text
     * and orders as text. Nothing is widened by it: a permission on a text column compares the text it holds.
     */
    static List<Case> cases() {
        return DifferentialCases.all().stream().filter(testCase -> !testCase.expression().contains("\"mood\": {\"_gt\"")).toList();
    }

    @Test
    void everyCaseRuns() {
        assertThat(cases()).hasSizeGreaterThanOrEqualTo(80);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void compiledFilter_keepsTheRowsNativePostgresKeeps(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());

        assertThat(sqlite.rowsKept(testCase.table(), compile(testCase, false))).isEqualTo(oracle);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void writeFilter_reachesTheRowsNativePostgresKeeps(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());

        assertThat(sqlite.rowsDeleted(testCase.table(), compile(testCase, true))).isEqualTo(oracle);
    }

    /** As an INSERT or UPDATE check the engine accepts exactly the rows native Postgres accepts. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void writeCheck_acceptsTheRowsNativePostgresAccepts_orIsRefused(Case testCase) throws SQLException {
        RequestGuard guard = sqlite.writeGuard(testCase.table(), parse(testCase.expression()));

        for (RlsOp operation : List.of(RlsOp.INSERT, RlsOp.UPDATE)) {
            boolean insert = operation == RlsOp.INSERT;
            if (guard.checkReads(testCase.table(), operation).contains(testCase.table())) {
                continue; // a check reading the table it writes is refused, not enforced
            }
            assertThat(sqlite.writesAccepted(testCase.table(), guard, insert)).as(operation.name())
                    .isEqualTo(nativeWritesAccepted(testCase.table(), testCase.nativeSql(), insert));
        }
    }

    private static SqlFragment compile(Case testCase, boolean write) {
        BoolExp expression = parse(testCase.expression());
        SessionBinding binding = new SessionBinding(VARIABLES);
        ParamNamer namer = new ParamNamer("perm");
        return write
                ? FilterCompiler.compileForWrite(SQLITE, expression, testCase.table(), "t", sqlite.schema(), binding, namer)
                : FilterCompiler.compile(SQLITE, expression, testCase.table(), "t", sqlite.schema(), binding, namer);
    }
}
