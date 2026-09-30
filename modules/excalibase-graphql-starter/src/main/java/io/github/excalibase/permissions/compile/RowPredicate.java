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
import io.github.excalibase.schema.SchemaInfo;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * Decides a permission expression for one row in memory, as Postgres would decide the compiled
 * filter: NULL comparisons are unknown and do not pass, {@code NOT unknown} is unknown. What memory
 * cannot decide exactly (relationships, {@code _exists}, collation-dependent order, types only the
 * database understands, a column the row does not carry) is {@link Outcome#NEEDS_DATABASE}.
 *
 * <p>Build one per (subscription, session): names are checked and variables bound when it is built,
 * so a missing variable fails then, not per row. Immutable and thread-safe once built.
 */
public final class RowPredicate {

    public enum Outcome { MATCH, NO_MATCH, NEEDS_DATABASE }

    @FunctionalInterface
    private interface Node {
        Truth eval(Map<String, Object> row);
    }

    private static final Node NEEDS_DATABASE = row -> Truth.NEEDS_DATABASE;

    private final Node root;

    private RowPredicate(Node root) {
        this.root = root;
    }

    public static RowPredicate compile(BoolExp expression, String tableKey, SchemaInfo schema, SessionBinding binding) {
        Objects.requireNonNull(expression, "expression");
        TableRef.resolve(schema, tableKey);
        return new RowPredicate(new Builder(Objects.requireNonNull(schema), Objects.requireNonNull(binding))
                .node(expression, tableKey));
    }

    /** @param row column name to value, as JDBC or the change feed delivers it; a missing key is "not carried" */
    public Outcome test(Map<String, Object> row) {
        return switch (root.eval(row)) {
            case TRUE -> Outcome.MATCH;
            case FALSE, UNKNOWN -> Outcome.NO_MATCH;
            case NEEDS_DATABASE -> Outcome.NEEDS_DATABASE;
        };
    }

    private record Builder(SchemaInfo schema, SessionBinding binding) {

        Node node(BoolExp expression, String tableKey) {
            return switch (expression) {
                case True ignored -> row -> Truth.TRUE;
                case And and -> fold(nodes(and.operands(), tableKey), Truth.TRUE, Truth::and);
                case Or or -> fold(nodes(or.operands(), tableKey), Truth.FALSE, Truth::or);
                case Not not -> negated(node(not.operand(), tableKey));
                case ColumnCompare compare -> compare(compare, tableKey);
                case Relationship relationship -> relationship(relationship, tableKey);
                case Exists exists -> {
                    TableRef.resolve(schema, exists.table());
                    node(exists.where(), exists.table());
                    yield NEEDS_DATABASE;
                }
            };
        }

        private List<Node> nodes(List<BoolExp> operands, String tableKey) {
            return operands.stream().map(operand -> node(operand, tableKey)).toList();
        }

        private static Node fold(List<Node> nodes, Truth identity, BinaryOperator<Truth> combine) {
            return row -> {
                Truth result = identity;
                for (Node node : nodes) {
                    result = combine.apply(result, node.eval(row));
                }
                return result;
            };
        }

        private static Node negated(Node node) {
            return row -> node.eval(row).not();
        }

        /** Checked and bound like the SQL path, then left to the database. */
        private Node relationship(Relationship relationship, String tableKey) {
            RelationshipJoin join = RelationshipJoin.resolve(schema, tableKey, relationship.name());
            TableRef.resolve(schema, join.targetTable());
            node(relationship.where(), join.targetTable());
            return NEEDS_DATABASE;
        }

        private Node compare(ColumnCompare compare, String tableKey) {
            String column = compare.column();
            return switch (compare.operator()) {
                case IS_NULL -> {
                    ColumnType.of(schema, tableKey, column);
                    boolean wanted = FilterCompiler.nullWanted(compare.operand());
                    yield row -> row.containsKey(column) ? Truth.of((row.get(column) == null) == wanted) : Truth.NEEDS_DATABASE;
                }
                case IN, NIN -> membership(compare, tableKey);
                case LIKE, NLIKE -> like(compare, tableKey);
                default -> scalar(compare, tableKey);
            };
        }

        private Node scalar(ColumnCompare compare, String tableKey) {
            ColumnType type = ColumnType.of(schema, tableKey, compare.column());
            Object operand = binding.value(compare.operand(), type);
            boolean ordering = compare.operator() != ComparisonOperator.EQ && compare.operator() != ComparisonOperator.NEQ;
            if (!type.equatableInMemory() || (ordering && !type.orderedInMemory())) {
                return NEEDS_DATABASE;
            }
            IntPredicate accepts = accepts(compare.operator());
            return onValue(compare.column(), type, value -> Truth.of(accepts.test(type.compare(value, operand))));
        }

        private Node membership(ColumnCompare compare, String tableKey) {
            ColumnType type = ColumnType.of(schema, tableKey, compare.column());
            List<Object> values = binding.values(compare.operand(), type);
            boolean positive = compare.operator() == ComparisonOperator.IN;
            if (values.isEmpty()) {
                return row -> Truth.of(!positive);
            }
            if (!type.equatableInMemory()) {
                return NEEDS_DATABASE;
            }
            Node anyEqual = onValue(compare.column(), type, value -> {
                Truth result = Truth.FALSE;
                for (Object element : values) {
                    result = result.or(element == null ? Truth.UNKNOWN : Truth.of(type.compare(value, element) == 0));
                }
                return result;
            });
            return positive ? anyEqual : negated(anyEqual);
        }

        private Node like(ColumnCompare compare, String tableKey) {
            ColumnType type = FilterCompiler.likeColumn(schema, tableKey, compare.column());
            Pattern pattern = LikePattern.compile((String) binding.value(compare.operand(), type));
            Node matches = onValue(compare.column(), type, value -> Truth.of(pattern.matcher((String) value).matches()));
            return compare.operator() == ComparisonOperator.LIKE ? matches : negated(matches);
        }

        /** Absent column: ask the database. NULL: unknown. A value of the wrong shape: ask the database. */
        private static Node onValue(String column, ColumnType type, Function<Object, Truth> test) {
            return row -> {
                if (!row.containsKey(column)) {
                    return Truth.NEEDS_DATABASE;
                }
                Object raw = row.get(column);
                if (raw == null) {
                    return Truth.UNKNOWN;
                }
                Object value;
                try {
                    value = type.canonical(raw);
                } catch (IllegalArgumentException e) {
                    return Truth.NEEDS_DATABASE;
                }
                return test.apply(value);
            };
        }

        private static IntPredicate accepts(ComparisonOperator operator) {
            return switch (operator) {
                case EQ -> order -> order == 0;
                case NEQ -> order -> order != 0;
                case GT -> order -> order > 0;
                case GTE -> order -> order >= 0;
                case LT -> order -> order < 0;
                case LTE -> order -> order <= 0;
                default -> throw new IllegalArgumentException("Not a scalar comparison: " + operator);
            };
        }
    }
}
