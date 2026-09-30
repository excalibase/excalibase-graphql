package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.compile.DifferentialCases.Case;
import io.github.excalibase.permissions.compile.DifferentialCases.Judge;
import io.github.excalibase.permissions.compile.RowPredicate.Outcome;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.U1;
import static io.github.excalibase.permissions.compile.FixtureSchema.U2;
import static io.github.excalibase.permissions.compile.FixtureSchema.U3;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Native Postgres row security is the oracle: for every case, the compiled filter and the in-memory
 * matcher keep exactly the rows a hand-written {@code CREATE POLICY} keeps, run as a non-owner role.
 */
class PermissionExpressionDifferentialTest {

    static final Map<String, String> VARIABLES = Map.of(
            "x-excalibase-user-id", U2,
            "x-excalibase-org-id", "1",
            "x-excalibase-team", "open",
            "x-excalibase-title-pattern", "A%",
            "x-excalibase-user-ids", "{\"" + U1 + "\",\"" + U3 + "\"}",
            "x-excalibase-org-ids", "{\"2\",\"3\"}",
            "x-excalibase-empty-ids", "{}",
            "x-excalibase-due", "2026-06-01",
            "x-excalibase-role-name", "admin");

    private static DriverManagerDataSource dataSource;
    private static SchemaInfo schema;

    @BeforeAll
    static void connect() {
        dataSource = DifferentialFixture.dataSource();
        schema = DifferentialFixture.schema();
    }

    static List<Case> cases() {
        return DifferentialCases.all();
    }

    @Test
    void theCaseListCoversAtLeastFiftyExpressions() {
        assertThat(cases()).hasSizeGreaterThanOrEqualTo(50);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void compiledFilter_keepsTheRowsNativePostgresKeeps(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());

        assertThat(engineRows(testCase.table(), parse(testCase.expression()))).isEqualTo(oracle);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rowPredicate_agreesWithNativePostgres(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());
        RowPredicate predicate = RowPredicate.compile(parse(testCase.expression()), testCase.table(), schema,
                new SessionBinding(VARIABLES));

        for (Map<String, Object> row : allRows(testCase.table())) {
            String key = DifferentialFixture.key(testCase.table(), row);
            Outcome outcome = predicate.test(row);
            assertJudged(testCase.judge(), outcome, oracle.contains(key), key);
        }
    }

    /** A deleted row is judged by its image alone, so the probe must agree with the policy on every row. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void imageProbe_agreesWithNativePostgres(Case testCase) throws SQLException {
        TreeSet<String> oracle = nativeRows(testCase.table(), testCase.nativeSql());
        BoolExp expression = parse(testCase.expression());
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);

        for (Map<String, Object> row : allRows(testCase.table())) {
            String key = DifferentialFixture.key(testCase.table(), row);
            SqlFragment probe = RowProbe.ofImage(expression, testCase.table(), row, schema,
                    new SessionBinding(VARIABLES), new ParamNamer("probe")).orElseThrow();
            Boolean kept = jdbc.queryForObject(probe.sql(), new MapSqlParameterSource(probe.params()), Boolean.class);
            assertThat(kept).as("row %s", key).isEqualTo(oracle.contains(key));
        }
    }

    private static void assertJudged(Judge judge, Outcome outcome, boolean kept, String key) {
        Outcome expected = kept ? Outcome.MATCH : Outcome.NO_MATCH;
        switch (judge) {
            case MEMORY -> assertThat(outcome).as("row %s", key).isEqualTo(expected);
            case DATABASE -> assertThat(outcome).as("row %s", key).isEqualTo(Outcome.NEEDS_DATABASE);
            case MIXED -> assertThat(outcome).as("row %s", key).isIn(expected, Outcome.NEEDS_DATABASE);
        }
    }

    @Test
    void missingVariable_failsTheRequestInsteadOfMatching() {
        BoolExp expression = parse("{\"org_id\": {\"_eq\": \"X-Excalibase-Tenant\"}}");

        assertThatThrownBy(() -> engineRows(NOTES, expression))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo("missing_session_variable");
    }

    @Test
    void wronglyTypedVariable_failsTheRequestInsteadOfMatching() {
        BoolExp expression = parse("{\"owner_id\": {\"_eq\": \"X-Excalibase-Team\"}}");

        assertThatThrownBy(() -> engineRows(NOTES, expression))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo("invalid_session_variable");
        assertThatThrownBy(() -> RowPredicate.compile(parse("{\"org_id\": {\"_in\": \"X-Excalibase-User-Ids\"}}"),
                NOTES, schema, new SessionBinding(VARIABLES)))
                .extracting("code").isEqualTo("invalid_session_variable");
    }

    private static TreeSet<String> engineRows(String table, BoolExp expression) {
        return DifferentialFixture.engineRows(table, expression, VARIABLES);
    }

    static TreeSet<String> nativeRows(String table, String predicate) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                setVariables(connection);
                statement.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                statement.execute("CREATE POLICY oracle ON " + table + " FOR SELECT TO app_role USING (" + predicate + ")");
                statement.execute("SET LOCAL ROLE app_role");
                return keys(statement, "SELECT " + DifferentialFixture.keySql(table, table.substring(7)) + " FROM " + table);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void setVariables(Connection connection) throws SQLException {
        try (PreparedStatement set = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
            for (Map.Entry<String, String> variable : VARIABLES.entrySet()) {
                String name = variable.getKey().substring("x-excalibase-".length()).replace('-', '_');
                set.setString(1, "x." + name.toLowerCase(Locale.ROOT));
                set.setString(2, variable.getValue());
                set.execute();
            }
        }
    }

    private static TreeSet<String> keys(Statement statement, String sql) throws SQLException {
        TreeSet<String> keys = new TreeSet<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                keys.add(rows.getString(1));
            }
        }
        return keys;
    }

    private static List<Map<String, Object>> allRows(String table) {
        return new ArrayList<>(new JdbcTemplate(dataSource).queryForList("SELECT * FROM " + table));
    }
}
