package io.github.excalibase.postgres;

import graphql.language.Field;
import io.github.excalibase.compiler.InsertRow;
import io.github.excalibase.compiler.MutationBuilder;
import io.github.excalibase.compiler.MutationSequence;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.security.PermissionCheckFailedException;
import io.github.excalibase.security.RlsOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.excalibase.compiler.SqlKeywords.AND;
import static io.github.excalibase.compiler.SqlKeywords.AS_OPEN;
import static io.github.excalibase.compiler.SqlKeywords.FROM;
import static io.github.excalibase.compiler.SqlKeywords.INSERT_INTO;
import static io.github.excalibase.compiler.SqlKeywords.P_NESTED_COUNT;
import static io.github.excalibase.compiler.SqlKeywords.P_NESTED_INSERT;
import static io.github.excalibase.compiler.SqlKeywords.P_NESTED_ROW;
import static io.github.excalibase.compiler.SqlKeywords.P_NESTED_ROWS;
import static io.github.excalibase.compiler.SqlKeywords.RETURNING_ALL;
import static io.github.excalibase.compiler.SqlKeywords.SELECT;
import static io.github.excalibase.compiler.SqlKeywords.VALUES;
import static io.github.excalibase.compiler.SqlKeywords.WHERE;
import static io.github.excalibase.compiler.SqlKeywords.WITH;
import static io.github.excalibase.compiler.SqlKeywords.joinCols;
import static io.github.excalibase.compiler.SqlKeywords.namedParam;
import static io.github.excalibase.compiler.SqlKeywords.param;
import static io.github.excalibase.compiler.SqlKeywords.parens;

/**
 * Compiles a create whose rows nest others into the statements Hasura runs for it, in its order: the
 * row an object relationship points at, then the row (which takes that row's key), then the rows of each
 * array relationship (which take the row's key). Every statement holds its own table's insert check, so
 * a row's check sees what earlier statements wrote and nothing later. Each row may set only the columns
 * its table's insert permission allows and gets that permission's presets; a key filled from another row
 * is not the client's to send. All of it is decided here, before any statement runs.
 */
final class PostgresNestedInsert {

    private final MutationBuilder shared;
    private final Map<String, Object> params;

    PostgresNestedInsert(MutationBuilder shared, Map<String, Object> params) {
        this.shared = shared;
        this.params = params;
    }

    /** The columns of a row filled from the row it hangs off: none at the root. */
    private record Inherited(String parentTable, List<String> columns, List<String> parentColumns) {
        static final Inherited NONE = new Inherited(null, List.of(), List.of());
    }

    /** The whole field: its root statements, and the statement answering it once they all ran. */
    MutationSequence.NestedInsert field(Field field, String responseKey, String table, List<InsertRow> rows,
                                        boolean many, String onConflictSql) {
        List<RlsOp> checks = onConflictSql.isEmpty() ? List.of(RlsOp.INSERT) : List.of(RlsOp.INSERT, RlsOp.UPDATE);
        List<MutationSequence.InsertNode> roots = nodes(table, rows, Inherited.NONE, onConflictSql, checks);
        String rowsParam = reserve(P_NESTED_ROWS);
        String countParam = reserve(P_NESTED_COUNT);
        return new MutationSequence.NestedInsert(responseKey, roots, output(field, table, many, rowsParam, countParam),
                rowsParam, countParam);
    }

    /**
     * Rows of one table under one parent. Rows that nest others are inserted one at a time so their own
     * key reaches their children, as Hasura does; flat rows share one statement.
     */
    private List<MutationSequence.InsertNode> nodes(String table, List<InsertRow> rows, Inherited inherited,
                                                    String onConflictSql, List<RlsOp> checks) {
        rows.forEach(row -> validate(table, row, inherited));
        boolean noColumns = inherited.columns().isEmpty() && shared.presets(table, RlsOp.INSERT).isEmpty()
                && rows.stream().allMatch(row -> row.columns().isEmpty());
        boolean oneByOne = noColumns || rows.stream().anyMatch(row -> !row.flat());
        if (oneByOne && rows.size() > 1) {
            return rows.stream().map(row -> node(table, List.of(row), inherited, onConflictSql, checks)).toList();
        }
        return List.of(node(table, rows, inherited, onConflictSql, checks));
    }

    private MutationSequence.InsertNode node(String table, List<InsertRow> rows, Inherited inherited,
                                             String onConflictSql, List<RlsOp> checks) {
        Map<String, Object> presets = shared.presets(table, RlsOp.INSERT);
        String parentRowParam = inherited.parentTable() == null ? null : reserve(P_NESTED_ROW);
        List<MutationSequence.Before> before = new ArrayList<>();
        List<MutationSequence.InsertNode> after = new ArrayList<>();
        List<Map<String, String>> valueRows = new ArrayList<>();
        for (InsertRow row : rows) {
            Map<String, String> values = new LinkedHashMap<>();
            presets.forEach((column, value) -> values.put(column, bind(table, column, value)));
            row.columns().forEach((column, value) -> values.putIfAbsent(column, bind(table, column, value)));
            for (InsertRow.Nested nested : row.nested()) {
                if (nested.array()) {
                    SchemaInfo.ReverseFkInfo relation = shared.schemaInfo()
                            .getArrayInsertRelationship(table, nested.relationship());
                    after.addAll(nodes(relation.childTable(), nested.rows(),
                            new Inherited(table, relation.fkColumns(), relation.refColumns()), "",
                            List.of(RlsOp.INSERT)));
                } else {
                    before.add(objectRelation(table, nested, values));
                }
            }
            for (int i = 0; i < inherited.columns().size(); i++) {
                values.putIfAbsent(inherited.columns().get(i),
                        fromRow(inherited.parentTable(), parentRowParam, inherited.parentColumns().get(i)));
            }
            valueRows.add(values);
        }
        String sql = statement(table, valueRows, onConflictSql, checks);
        return new MutationSequence.InsertNode(table, sql, parentRowParam, before, after);
    }

    /** The row the relationship points at, inserted first; its key fills the row's foreign key. */
    private MutationSequence.Before objectRelation(String table, InsertRow.Nested nested, Map<String, String> values) {
        SchemaInfo.FkInfo relation = shared.schemaInfo().getObjectInsertRelationship(table, nested.relationship());
        MutationSequence.InsertNode target = nodes(relation.refTable(), nested.rows(), Inherited.NONE, "",
                List.of(RlsOp.INSERT)).getFirst();
        String rowParam = reserve(P_NESTED_ROW);
        for (int i = 0; i < relation.fkColumns().size(); i++) {
            values.putIfAbsent(relation.fkColumns().get(i),
                    fromRow(relation.refTable(), rowParam, relation.refColumns().get(i)));
        }
        return new MutationSequence.Before(nested.relationship(), rowParam, target);
    }

    /**
     * Hasura's checks before anything is written: a row may set only what its insert permission allows,
     * never a key its parent fills, and never a key beside the object relationship that fills it.
     */
    private void validate(String table, InsertRow row, Inherited inherited) {
        shared.requireSettable(table, RlsOp.INSERT, row.columns().keySet());
        List<String> determined = inherited.columns().stream().filter(row.columns()::containsKey).toList();
        if (!determined.isEmpty()) {
            throw new IllegalArgumentException("cannot insert " + quoted(determined)
                    + " columns as their values are already being determined by parent insert");
        }
        for (InsertRow.Nested nested : row.nested()) {
            if (nested.array()) continue;
            SchemaInfo.FkInfo relation = shared.schemaInfo().getObjectInsertRelationship(table, nested.relationship());
            List<String> conflicts = relation.fkColumns().stream()
                    .filter(column -> row.columns().containsKey(column) || inherited.columns().contains(column))
                    .toList();
            if (!conflicts.isEmpty()) {
                throw new IllegalArgumentException("cannot insert object relationship \"" + nested.relationship()
                        + "\" as " + quoted(conflicts) + " column values are already determined");
            }
        }
    }

    /**
     * {@code INSERT … RETURNING *} answering how many rows it wrote and those rows as JSON, raising when a
     * written row fails its check. A column one row leaves out takes its default.
     */
    private String statement(String table, List<Map<String, String>> valueRows, String onConflictSql,
                             List<RlsOp> checks) {
        String alias = shared.dialect().randAlias();
        Set<String> columns = new LinkedHashSet<>();
        valueRows.forEach(values -> columns.addAll(values.keySet()));
        String insert;
        if (columns.isEmpty()) {
            insert = " DEFAULT VALUES";
        } else {
            List<String> tuples = valueRows.stream()
                    .map(values -> parens(joinCols(columns.stream()
                            .map(column -> values.getOrDefault(column, "DEFAULT")).toList())))
                    .toList();
            insert = " " + parens(joinCols(columns.stream().map(shared.dialect()::quoteIdentifier).toList()))
                    + VALUES + joinCols(tuples);
        }
        String cte = WITH + alias + AS_OPEN + INSERT_INTO + shared.qualifiedTable(table) + insert + onConflictSql
                + RETURNING_ALL;
        String count = "(" + SELECT + "count(*)" + FROM + alias + ")";
        List<String> violations = WriteChecks.violations(
                List.of(new WriteChecks.WrittenRows(alias, table, checks)), params, shared);
        if (!violations.isEmpty()) {
            String raise = PermissionCheckFailedException.raiseWhen(String.join(" OR ", violations));
            count = "CASE WHEN " + raise + " IS NULL THEN " + count + " END";
        }
        String row = shared.dialect().randAlias();
        return cte + " " + SELECT + count + ", (" + SELECT + "COALESCE(jsonb_agg(to_jsonb(" + row
                + ")), '[]'::jsonb)::text" + FROM + alias + " " + row + ")";
    }

    /**
     * Answers the field after every insert ran, from the root rows: the rows the role may select, with
     * their relationships as they now stand, or, for a role that may not select the table, the number of
     * rows the whole nested insert wrote.
     */
    private String output(Field field, String table, boolean many, String rowsParam, String countParam) {
        if (!shared.returnsRows(table)) {
            return SELECT + shared.queryBuilder().buildAffectedRows(field.getSelectionSet(),
                    "CAST(" + param(countParam) + " AS integer)");
        }
        String alias = shared.dialect().randAlias();
        String objectSql = shared.queryBuilder().buildObject(field.getSelectionSet(), table, alias, params);
        List<String> readable = new ArrayList<>();
        shared.filterBuilder().appendRlsConditions(readable, table, alias, params, RlsOp.SELECT);
        String value = many ? shared.dialect().coalesceArray(shared.dialect().aggregateArray(objectSql)) : objectSql;
        return WITH + alias + AS_OPEN + SELECT + "*" + FROM + "jsonb_populate_recordset(NULL::"
                + shared.qualifiedTable(table) + ", CAST(" + param(rowsParam) + " AS jsonb)))"
                + " " + SELECT + value + FROM + alias + (readable.isEmpty() ? "" : WHERE + String.join(AND, readable));
    }

    private String bind(String table, String column, Object value) {
        String name = namedParam(P_NESTED_INSERT, column, params.size());
        params.put(name, shared.convertCompositeValue(table, column, value));
        return param(name) + shared.getEnumCastForMutation(table, column);
    }

    /** One column of the row bound to {@code rowParam} (a one-row JSON array), typed as its table has it. */
    private String fromRow(String table, String rowParam, String column) {
        String source = shared.dialect().randAlias();
        return "(" + SELECT + source + "." + shared.dialect().quoteIdentifier(column) + FROM
                + "jsonb_populate_record(NULL::" + shared.qualifiedTable(table) + ", CAST(" + param(rowParam)
                + " AS jsonb) -> 0) " + source + ")";
    }

    /** A parameter the statements are run with, named now so no compiled parameter can take its name. */
    private String reserve(String prefix) {
        String name = namedParam(prefix, params.size());
        params.put(name, null);
        return name;
    }

    private static String quoted(List<String> columns) {
        return String.join(", ", columns.stream().map(column -> "\"" + column + "\"").toList());
    }
}
