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
 * Compiles a permission expression (docs/features/permissions.md §4) to a boolean SQL expression in the
 * project's dialect over the table aliased {@code outerAlias}. Every value is a bind parameter; every
 * identifier comes from the reflected schema and is quoted. Relationships and {@code _exists} become
 * {@code EXISTS} subqueries, so SQL NULL semantics hold exactly as the database applies them.
 */
public final class FilterCompiler {

    private FilterCompiler() {
    }

    /**
     * @param tableKey   the table as {@link SchemaInfo} keys it ({@code public.orders})
     * @param outerAlias how the calling query names that table: a plain identifier or an alias the
     *                   engine's dialect already quoted ({@code "a1b2c3d4"}, {@code `a1b2c3d4`})
     * @param namer      the request's namer, so names stay unique across every fragment of the request
     */
    public static SqlFragment compile(PermissionDialect dialect, BoolExp expression, String tableKey, String outerAlias,
                                      SchemaInfo schema, SessionBinding binding, ParamNamer namer) {
        return compile(dialect, expression, tableKey, outerAlias, schema, binding, namer, Mode.READ);
    }

    /**
     * As {@link #compile}, for the WHERE of an UPDATE or DELETE of {@code tableKey}: where the database
     * refuses a statement that reads the table it writes (MySQL), that table is read through a derived table.
     */
    public static SqlFragment compileForWrite(PermissionDialect dialect, BoolExp expression, String tableKey,
                                              String outerAlias, SchemaInfo schema, SessionBinding binding,
                                              ParamNamer namer) {
        return compile(dialect, expression, tableKey, outerAlias, schema, binding, namer, Mode.WRITE);
    }

    /** Over an image row named {@code alias} rather than a table: its columns hold values, see {@link RowProbe}. */
    static SqlFragment compileOverImage(PermissionDialect dialect, BoolExp expression, String tableKey, String alias,
                                        SchemaInfo schema, SessionBinding binding, ParamNamer namer) {
        return compile(dialect, expression, tableKey, alias, schema, binding, namer, Mode.IMAGE);
    }

    private enum Mode { READ, WRITE, IMAGE }

    private static SqlFragment compile(PermissionDialect dialect, BoolExp expression, String tableKey,
                                       String outerAlias, SchemaInfo schema, SessionBinding binding, ParamNamer namer,
                                       Mode mode) {
        Objects.requireNonNull(expression, "expression");
        TableRef table = TableRef.resolve(dialect, schema, tableKey);
        if (outerAlias == null || !dialect.sql().safeAlias(outerAlias)) {
            throw new IllegalArgumentException("Unsafe table alias");
        }
        Walker walker = new Walker(dialect, Objects.requireNonNull(schema), Objects.requireNonNull(binding),
                Objects.requireNonNull(namer), mode, table.key(), outerAlias);
        String sql = walker.expression(expression, table, outerAlias);
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

    static ColumnType likeColumn(PermissionDialect dialect, SchemaInfo schema, String tableKey, String column) {
        ColumnType type = ColumnType.of(dialect, schema, tableKey, column);
        if (!type.textual()) {
            throw new PermissionEvaluationException(PermissionEvaluationException.UNSUPPORTED_OPERATOR,
                    "_like and _nlike apply to text columns only");
        }
        return type;
    }

    private static final class Walker {

        private final PermissionDialect dialect;
        private final SchemaInfo schema;
        private final SessionBinding binding;
        private final ParamNamer namer;
        private final Mode mode;
        private final String rootTable;
        private final String rootAlias;
        private final Map<String, Object> params = new LinkedHashMap<>();

        Walker(PermissionDialect dialect, SchemaInfo schema, SessionBinding binding, ParamNamer namer, Mode mode,
               String rootTable, String rootAlias) {
            this.dialect = dialect;
            this.schema = schema;
            this.binding = binding;
            this.namer = namer;
            this.mode = mode;
            this.rootTable = rootTable;
            this.rootAlias = rootAlias;
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

        private boolean image(String alias) {
            return mode == Mode.IMAGE && alias.equals(rootAlias);
        }

        private String compare(ColumnCompare compare, TableRef table, String alias) {
            String reference = alias + "." + dialect.quote(compare.column());
            ColumnType type = ColumnType.of(dialect, schema, table.key(), compare.column());
            if (compare.operator() == ComparisonOperator.IS_NULL) {
                return reference + (nullWanted(compare.operand()) ? " IS NULL" : " IS NOT NULL");
            }
            type.requireComparable();
            String column = type.compared(reference, image(alias));
            return switch (compare.operator()) {
                case IN, NIN -> membership(compare, type, column);
                case LIKE, NLIKE -> like(compare, table, column);
                default -> scalar(compare, type, column);
            };
        }

        private String scalar(ColumnCompare compare, ColumnType type, String column) {
            Object value = binding.value(compare.operand(), type);
            NonFinite special = NonFinite.of(dialect, type, value);
            if (special != null) {
                return special.comparedWith(compare.operator(), column);
            }
            return column + " " + symbol(compare.operator()) + " " + bind(value, type);
        }

        private String membership(ColumnCompare compare, ColumnType type, String column) {
            boolean positive = compare.operator() == ComparisonOperator.IN;
            List<Object> values = binding.values(compare.operand(), type);
            if (values.isEmpty()) {
                return positive ? "FALSE" : "TRUE";
            }
            List<String> placeholders = new ArrayList<>(values.size());
            values.stream().filter(value -> NonFinite.of(dialect, type, value) == null)
                    .forEach(value -> placeholders.add(bind(value, type)));
            if (placeholders.isEmpty()) {
                return NonFinite.noneEqual(positive, column);
            }
            return column + (positive ? " IN (" : " NOT IN (") + String.join(", ", placeholders) + ")";
        }

        private String like(ColumnCompare compare, TableRef table, String column) {
            ColumnType type = likeColumn(dialect, schema, table.key(), compare.column());
            Object pattern = binding.value(compare.operand(), type);
            LikePattern.compile((String) pattern);
            String operator = compare.operator() == ComparisonOperator.LIKE ? " LIKE " : " NOT LIKE ";
            return column + operator + bind(pattern, type);
        }

        private String relationship(Relationship relationship, TableRef table, String alias) {
            RelationshipJoin join = RelationshipJoin.resolve(schema, table.key(), relationship.name());
            TableRef target = TableRef.resolve(dialect, schema, join.targetTable());
            String inner = namer.nextAlias();
            List<String> conditions = new ArrayList<>();
            for (int i = 0; i < join.targetColumns().size(); i++) {
                conditions.add(joinCondition(target, inner, join.targetColumns().get(i), table, alias,
                        join.outerColumns().get(i)));
            }
            conditions.add(expression(relationship.where(), target, inner));
            return "EXISTS (SELECT 1 FROM " + from(target) + " " + inner + " WHERE "
                    + String.join(" AND ", conditions) + ")";
        }

        /** Over an image row the outer value is held as comparisons read it, so the inner column is read so too. */
        private String joinCondition(TableRef target, String inner, String targetColumn, TableRef table, String alias,
                                     String outerColumn) {
            String innerReference = inner + "." + dialect.quote(targetColumn);
            String outerReference = alias + "." + dialect.quote(outerColumn);
            if (!image(alias)) {
                return innerReference + " = " + outerReference;
            }
            return ColumnType.of(dialect, schema, target.key(), targetColumn).compared(innerReference, false) + " = "
                    + ColumnType.of(dialect, schema, table.key(), outerColumn).compared(outerReference, true);
        }

        private String exists(Exists exists) {
            TableRef target = TableRef.resolve(dialect, schema, exists.table());
            String inner = namer.nextAlias();
            return "EXISTS (SELECT 1 FROM " + from(target) + " " + inner + " WHERE "
                    + expression(exists.where(), target, inner) + ")";
        }

        private String from(TableRef target) {
            boolean derived = mode == Mode.WRITE && target.key().equals(rootTable)
                    && dialect.sql().readsWrittenTableThroughDerivedTable();
            return derived ? "(SELECT * FROM " + target.sql() + ")" : target.sql();
        }

        private String bind(Object value, ColumnType type) {
            String name = namer.nextParam();
            params.put(name, value == null ? null : type.sqlValue(value));
            return type.placeholder(name);
        }
    }
}
