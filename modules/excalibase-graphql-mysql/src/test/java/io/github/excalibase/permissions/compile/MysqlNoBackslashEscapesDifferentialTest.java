package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.compile.DifferentialCases.Case;
import io.github.excalibase.permissions.compile.MysqlDifferentialFixture.Collation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.List;
import java.util.TreeSet;

import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static io.github.excalibase.permissions.compile.PermissionDialect.MYSQL;
import static io.github.excalibase.permissions.compile.PermissionExpressionDifferentialTest.VARIABLES;
import static io.github.excalibase.permissions.compile.PermissionExpressionDifferentialTest.nativeRows;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL's default LIKE escape is a backslash only while NO_BACKSLASH_ESCAPES is off. Under that sql_mode the
 * compiled LIKE cases, escaped {@code %} and {@code _} included, must still keep the rows native Postgres keeps;
 * it is the stated {@code ESCAPE CHAR(92)} that makes it so.
 */
class MysqlNoBackslashEscapesDifferentialTest {

    private static MysqlDifferentialFixture mysql;

    @BeforeAll
    static void start() {
        PermissionExpressionDifferentialTest.connect();
        mysql = MysqlDifferentialFixture.of(Collation.BIN, true);
    }

    static List<Case> likeCases() {
        return DifferentialCases.all().stream()
                .filter(testCase -> testCase.expression().contains("_like") || testCase.expression().contains("_nlike"))
                .toList();
    }

    @Test
    void theEscapedCasesAreAmongThem() {
        assertThat(likeCases()).anyMatch(testCase -> testCase.expression().contains("\\\\%"))
                .anyMatch(testCase -> testCase.expression().contains("\\\\_"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("likeCases")
    void compiledFilter_keepsTheRowsNativePostgresKeeps(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());

        assertThat(mysql.rowsKept(testCase.table(), compile(testCase, false))).isEqualTo(oracle);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("likeCases")
    void writeFilter_reachesTheRowsNativePostgresKeeps(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());

        assertThat(mysql.rowsDeleted(testCase.table(), compile(testCase, true))).isEqualTo(oracle);
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
