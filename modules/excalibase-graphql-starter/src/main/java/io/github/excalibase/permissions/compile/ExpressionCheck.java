package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.BoolExp.And;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import io.github.excalibase.permissions.BoolExp.Exists;
import io.github.excalibase.permissions.BoolExp.Not;
import io.github.excalibase.permissions.BoolExp.Or;
import io.github.excalibase.permissions.BoolExp.Relationship;
import io.github.excalibase.permissions.BoolExp.True;
import io.github.excalibase.permissions.ComparisonOperator;
import io.github.excalibase.permissions.Literal;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.schema.SchemaInfo;

import java.util.Map;

/**
 * Checks a permission expression against the reflected schema before any request exists: every
 * table, column and relationship must be real and every literal must fit its column. Session
 * variables are left for the request, which is the only thing that can supply them.
 */
public final class ExpressionCheck {

    private static final SessionBinding NO_SESSION = new SessionBinding(Map.of());

    private ExpressionCheck() {
    }

    /** @throws PermissionEvaluationException naming what does not fit */
    public static void check(PermissionDialect dialect, BoolExp expression, String tableKey, SchemaInfo schema) {
        TableRef.resolve(dialect, schema, tableKey);
        walk(dialect, expression, tableKey, schema);
    }

    private static void walk(PermissionDialect dialect, BoolExp expression, String tableKey, SchemaInfo schema) {
        switch (expression) {
            case True ignored -> { }
            case And and -> and.operands().forEach(operand -> walk(dialect, operand, tableKey, schema));
            case Or or -> or.operands().forEach(operand -> walk(dialect, operand, tableKey, schema));
            case Not not -> walk(dialect, not.operand(), tableKey, schema);
            case ColumnCompare compare -> compare(dialect, compare, tableKey, schema);
            case Relationship(String name, BoolExp where) ->
                    check(dialect, where, RelationshipJoin.resolve(schema, tableKey, name).targetTable(), schema);
            case Exists(String table, BoolExp where) -> check(dialect, where, table, schema);
        }
    }

    private static void compare(PermissionDialect dialect, ColumnCompare compare, String tableKey, SchemaInfo schema) {
        ColumnType type = ColumnType.of(dialect, schema, tableKey, compare.column());
        if (compare.operator() != ComparisonOperator.IS_NULL) {
            type.requireComparable();
        }
        switch (compare.operator()) {
            case IS_NULL -> FilterCompiler.nullWanted(compare.operand());
            case LIKE, NLIKE -> {
                FilterCompiler.likeColumn(dialect, schema, tableKey, compare.column());
                if (compare.operand() instanceof Literal) {
                    LikePattern.compile((String) NO_SESSION.value(compare.operand(), type));
                }
            }
            case IN, NIN -> {
                if (compare.operand() instanceof Literal) {
                    NO_SESSION.values(compare.operand(), type);
                }
            }
            default -> {
                if (compare.operand() instanceof Literal) {
                    NO_SESSION.value(compare.operand(), type);
                }
            }
        }
    }
}
