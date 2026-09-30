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
import io.github.excalibase.permissions.Operand;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles a permission expression (docs/features/permissions.md §4) to a Postgres boolean SQL
 * expression over the table aliased {@code outerAlias}. Every value is a bind parameter; every
 * identifier comes from the reflected schema and is quoted. Relationships and {@code _exists}
 * become {@code EXISTS} subqueries, so SQL NULL semantics hold exactly as Postgres applies them.
 */
public final class FilterCompiler {

    private FilterCompiler() {
    }

    /**
     * @param tableKey   the table as {@link SchemaInfo} keys it ({@code public.orders})
     * @param outerAlias how the calling query names that table: a plain identifier or an
     *                   already-quoted alias ({@code "a1b2c3d4"})
     * @param namer      the request's namer, so names stay unique across every fragment of the request
     */
    public static SqlFragment compile(BoolExp expression, String tableKey, String outerAlias, SchemaInfo schema,
                                      SessionBinding binding, ParamNamer namer) {
        Objects.requireNonNull(expression, "expression");
        TableRef table = TableRef.resolve(schema, tableKey);
        String alias = SqlNames.checkAlias(outerAlias);
        Walker walker = new Walker(Objects.requireNonNull(schema), Objects.requireNonNull(binding),
                Objects.requireNonNull(namer));
        String sql = walker.expression(expression, table, alias);
        return new SqlFragment(sql, walker.params);
    }

    /** {@code _is_null}'s operand: true for IS NULL, false for IS NOT NULL. */
    static boolean nullWanted(Operand operand) {
        if (operand instanceof Literal(Boolean wanted)) {
            return wanted;
        }
        throw new PermissionEvaluationException(PermissionEvaluationException.INVALID_LITERAL,
                "_is_null takes true or false");
    }

    static String symbol(ComparisonOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NEQ -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            default -> throw new IllegalArgumentException("Not a scalar comparison: " + operator);
        };
    }

    static ColumnType likeColumn(SchemaInfo schema, String tableKey, String column) {
        ColumnType type = ColumnType.of(schema, tableKey, column);
        if (!type.textual()) {
            throw new PermissionEvaluationException(PermissionEvaluationException.UNSUPPORTED_OPERATOR,
                    "_like and _nlike apply to text columns only");
        }
        return type;
    }

    private static final class Walker {

        private final SchemaInfo schema;
        private final SessionBinding binding;
        private final ParamNamer namer;
        private final Map<String, Object> params = new LinkedHashMap<>();

        Walker(SchemaInfo schema, SessionBinding binding, ParamNamer namer) {
            this.schema = schema;
            this.binding = binding;
            this.namer = namer;
        }

        String expression(BoolExp expression, TableRef table, String alias) {
            return switch (expression) {
                case True ignored -> "TRUE";
                case And and -> joined(and.operands(), " AND ", "TRUE", table, alias);
                case Or or -> joined(or.operands(), " OR ", "FALSE", table, alias);
                case Not not -> "(NOT " + expression(not.operand(), table, alias) + ")";
                case ColumnCompare compare -> compare(compare, table, alias);
                case Relationship relationship -> relationship(relationship, table, alias);
                case Exists exists -> exists(exists);
            };
        }

        private String joined(List<BoolExp> operands, String operator, String empty, TableRef table, String alias) {
            if (operands.isEmpty()) {
                return empty;
            }
            List<String> parts = new ArrayList<>(operands.size());
            operands.forEach(operand -> parts.add(expression(operand, table, alias)));
            return "(" + String.join(operator, parts) + ")";
        }

        private String compare(ColumnCompare compare, TableRef table, String alias) {
            String column = alias + "." + SqlNames.quote(compare.column());
            return switch (compare.operator()) {
                case IS_NULL -> {
                    ColumnType.of(schema, table.key(), compare.column());
                    yield column + (nullWanted(compare.operand()) ? " IS NULL" : " IS NOT NULL");
                }
                case IN, NIN -> membership(compare, table, column);
                case LIKE, NLIKE -> like(compare, table, column);
                default -> {
                    ColumnType type = ColumnType.of(schema, table.key(), compare.column());
                    yield column + " " + symbol(compare.operator()) + " " + bind(binding.value(compare.operand(), type), type);
                }
            };
        }

        private String membership(ColumnCompare compare, TableRef table, String column) {
            ColumnType type = ColumnType.of(schema, table.key(), compare.column());
            boolean positive = compare.operator() == ComparisonOperator.IN;
            List<Object> values = binding.values(compare.operand(), type);
            if (values.isEmpty()) {
                return positive ? "FALSE" : "TRUE";
            }
            List<String> placeholders = new ArrayList<>(values.size());
            values.forEach(value -> placeholders.add(bind(value, type)));
            return column + (positive ? " IN (" : " NOT IN (") + String.join(", ", placeholders) + ")";
        }

        private String like(ColumnCompare compare, TableRef table, String column) {
            ColumnType type = likeColumn(schema, table.key(), compare.column());
            Object pattern = binding.value(compare.operand(), type);
            LikePattern.compile((String) pattern);
            String operator = compare.operator() == ComparisonOperator.LIKE ? " LIKE " : " NOT LIKE ";
            return column + operator + bind(pattern, type);
        }

        private String relationship(Relationship relationship, TableRef table, String alias) {
            RelationshipJoin join = RelationshipJoin.resolve(schema, table.key(), relationship.name());
            TableRef target = TableRef.resolve(schema, join.targetTable());
            String inner = namer.nextAlias();
            List<String> conditions = new ArrayList<>();
            for (int i = 0; i < join.targetColumns().size(); i++) {
                conditions.add(inner + "." + SqlNames.quote(join.targetColumns().get(i))
                        + " = " + alias + "." + SqlNames.quote(join.outerColumns().get(i)));
            }
            conditions.add(expression(relationship.where(), target, inner));
            return "EXISTS (SELECT 1 FROM " + target.sql() + " " + inner + " WHERE "
                    + String.join(" AND ", conditions) + ")";
        }

        private String exists(Exists exists) {
            TableRef target = TableRef.resolve(schema, exists.table());
            String inner = namer.nextAlias();
            return "EXISTS (SELECT 1 FROM " + target.sql() + " " + inner + " WHERE "
                    + expression(exists.where(), target, inner) + ")";
        }

        private String bind(Object value, ColumnType type) {
            String name = namer.nextParam();
            params.put(name, value == null ? null : type.sqlValue(value));
            return type.placeholder(name);
        }
    }
}
