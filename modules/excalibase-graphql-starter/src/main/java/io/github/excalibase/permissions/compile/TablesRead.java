package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.BoolExp.And;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import io.github.excalibase.permissions.BoolExp.Exists;
import io.github.excalibase.permissions.BoolExp.Not;
import io.github.excalibase.permissions.BoolExp.Or;
import io.github.excalibase.permissions.BoolExp.Relationship;
import io.github.excalibase.permissions.BoolExp.True;
import io.github.excalibase.schema.SchemaInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The tables an expression reads beyond the row it judges: every relationship's and {@code _exists}'s
 * table, at any depth. A check whose subqueries read a table its own write changes sees that write unless
 * it runs inside the statement, which only Postgres can do.
 */
public final class TablesRead {

    private TablesRead() {
    }

    public static Set<String> of(BoolExp expression, String tableKey, SchemaInfo schema) {
        Set<String> tables = new LinkedHashSet<>();
        walk(expression, tableKey, schema, tables);
        return Set.copyOf(tables);
    }

    private static void walk(BoolExp expression, String tableKey, SchemaInfo schema, Set<String> tables) {
        switch (expression) {
            case True _, ColumnCompare _ -> {
                // The row's own columns: no table read.
            }
            case And(List<BoolExp> operands) -> operands.forEach(operand -> walk(operand, tableKey, schema, tables));
            case Or(List<BoolExp> operands) -> operands.forEach(operand -> walk(operand, tableKey, schema, tables));
            case Not(BoolExp operand) -> walk(operand, tableKey, schema, tables);
            case Relationship(String name, BoolExp where) -> {
                String target = RelationshipJoin.resolve(schema, tableKey, name).targetTable();
                tables.add(target);
                walk(where, target, schema, tables);
            }
            case Exists(String table, BoolExp where) -> {
                tables.add(table);
                walk(where, table, schema, tables);
            }
        }
    }
}
