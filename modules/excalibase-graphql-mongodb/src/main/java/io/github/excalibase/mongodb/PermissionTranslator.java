package io.github.excalibase.mongodb;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import io.github.excalibase.permissions.ColumnSet;
import io.github.excalibase.permissions.ComparisonOperator;
import io.github.excalibase.permissions.Literal;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.SelectPermission;
import io.github.excalibase.permissions.SessionVariable;
import io.github.excalibase.permissions.TablePermissions;
import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.MaskMode;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the engine's permission document (the Hasura-model {@code permissions.yaml}) into the policies the plugin
 * enforces. Only what can be enforced exactly is accepted: a plain comparison, or one flat {@code _and} / {@code _or}
 * of comparisons, with literals and session variables. Anything else is refused when the document loads, never
 * weakened and never skipped, because a rule that is dropped is a door that is open.
 *
 * <p>Nothing here builds a MongoDB filter. The result is rls-core rules, which {@link RowSecurity} compiles from a
 * closed set of operators.
 */
final class PermissionTranslator {

    private static final Logger log = LoggerFactory.getLogger(PermissionTranslator.class);

    private static final String ID = CollectionDef.ID_FIELD;
    private static final String CLAIM_OPEN = "{{";
    private static final String CLAIM_CLOSE = "}}";
    private static final String PERMISSION_ON = "Permission on \"";

    private static final Map<ComparisonOperator, RuleOperator> OPERATORS = Map.of(
            ComparisonOperator.EQ, RuleOperator.EQ, ComparisonOperator.NEQ, RuleOperator.NEQ,
            ComparisonOperator.GT, RuleOperator.GT, ComparisonOperator.GTE, RuleOperator.GTE,
            ComparisonOperator.LT, RuleOperator.LT, ComparisonOperator.LTE, RuleOperator.LTE,
            ComparisonOperator.IN, RuleOperator.IN, ComparisonOperator.NIN, RuleOperator.NOT_IN,
            ComparisonOperator.LIKE, RuleOperator.LIKE, ComparisonOperator.NLIKE, RuleOperator.NOT_LIKE);

    /** What the plugin enforces: row rules and the column rules that hide what a role's column list leaves out. */
    record Translation(List<Policy> policies, List<ColumnPolicy> columnPolicies) {
        Translation {
            policies = List.copyOf(policies);
            columnPolicies = List.copyOf(columnPolicies);
        }
    }

    private PermissionTranslator() {
    }

    /**
     * @param database the declared database; a permission names its collections as {@code database.collection}
     * @throws IllegalStateException a permission that cannot be enforced exactly, or names something not declared
     */
    static Translation translate(PermissionSet document, String database, Catalog catalog) {
        List<Policy> policies = new ArrayList<>();
        List<ColumnPolicy> columns = new ArrayList<>();
        Set<String> readable = new LinkedHashSet<>();
        int ignoredWrites = 0;
        for (TablePermissions table : document.tables()) {
            CollectionDef def = collection(table.table(), database, catalog);
            if (table.insert().isPresent() || table.update().isPresent() || table.delete().isPresent()) {
                ignoredWrites++;
            }
            if (table.select().isPresent()) {
                addSelect(def, table, table.select().get(), policies, columns, readable);
            }
        }
        for (CollectionDef def : catalog.all()) {
            if (!readable.contains(def.name())) {
                policies.add(matchNothing(def));
            }
        }
        if (ignoredWrites > 0) {
            log.warn("MongoDB is read-only in this release: {} insert/update/delete permission(s) grant nothing", ignoredWrites);
        }
        return new Translation(policies, columns);
    }

    private static CollectionDef collection(String table, String database, Catalog catalog) {
        int dot = table.indexOf('.');
        String schema = dot < 0 ? "" : table.substring(0, dot);
        String name = dot < 0 ? table : table.substring(dot + 1);
        if (!schema.equals(database)) {
            throw new IllegalStateException(PERMISSION_ON + table + "\": a MongoDB collection is named "
                    + database + ".<collection>");
        }
        return catalog.collection(name).orElseThrow(() -> new IllegalStateException(
                PERMISSION_ON + table + "\": " + name + " is not a declared collection"));
    }

    private static void addSelect(CollectionDef def, TablePermissions table, SelectPermission select,
                                  List<Policy> policies, List<ColumnPolicy> columns, Set<String> readable) {
        String where = PERMISSION_ON + table.table() + "\" for role " + table.role() + ": ";
        if (select.limit() != null) {
            throw new IllegalStateException(where + "a row limit per role cannot be enforced on MongoDB yet");
        }
        Assignment role = Assignment.role(table.role());
        hiddenColumns(def, select.columns(), where).ifPresent(hidden -> columns.add(new ColumnPolicy(
                "col-" + table.table() + "-" + table.role(), table.table() + " " + table.role(), def.name(), hidden,
                Set.of(Operation.SELECT), MaskMode.HIDE, null, null, 0, true, List.of(role))));
        Shape shape = shape(select.filter(), where);
        if (shape == null) {
            return;
        }
        List<Rule> rules = shape.compares().isEmpty() ? List.of(isNotNull(ID))
                : shape.compares().stream().map(compare -> rule(def, compare, where)).toList();
        policies.add(new Policy("perm-" + table.table() + "-" + table.role(), table.table() + " " + table.role(),
                def.name(), PolicyEffect.ALLOW, Set.of(Operation.SELECT), shape.logic(), 0, true, rules, List.of(role)));
        readable.add(def.name());
    }

    /** The declared fields a column list leaves out; the id is always served, so a list without it is refused. */
    private static Optional<Set<String>> hiddenColumns(CollectionDef def, ColumnSet columns, String where) {
        if (columns.all()) {
            return Optional.empty();
        }
        for (String name : columns.names()) {
            if (!def.declares(name) && !CollectionDef.MONGO_ID.equals(name)) {
                throw new IllegalStateException(where + "column \"" + name + "\" is not declared on " + def.name());
            }
        }
        if (!columns.permits(ID) && !columns.permits(CollectionDef.MONGO_ID)) {
            throw new IllegalStateException(where + "the column list must include id, which every request needs");
        }
        Set<String> hidden = new LinkedHashSet<>(def.fields().keySet());
        hidden.removeAll(columns.names());
        return hidden.isEmpty() ? Optional.empty() : Optional.of(hidden);
    }

    /** A flat group of comparisons and how they combine; null when the role matches nothing at all ({@code _or: []}). */
    private record Shape(LogicOperator logic, List<ColumnCompare> compares) {
    }

    private static Shape shape(BoolExp filter, String where) {
        return switch (filter) {
            case BoolExp.True ignored -> new Shape(LogicOperator.AND, List.of());
            case ColumnCompare compare -> new Shape(LogicOperator.AND, List.of(compare));
            case BoolExp.And and -> new Shape(LogicOperator.AND, flat(and.operands(), where));
            case BoolExp.Or or -> or.operands().isEmpty() ? null : new Shape(LogicOperator.OR, flat(or.operands(), where));
            case BoolExp.Not ignored -> throw refuse(where, "_not cannot be enforced on MongoDB yet");
            case BoolExp.Relationship ignored -> throw refuse(where, "a relationship filter cannot be enforced on MongoDB yet");
            case BoolExp.Exists ignored -> throw refuse(where, "_exists cannot be enforced on MongoDB yet");
            case BoolExp.SessionCompare ignored ->
                    throw refuse(where, "a session variable check cannot be enforced on MongoDB yet");
        };
    }

    private static List<ColumnCompare> flat(List<BoolExp> operands, String where) {
        List<ColumnCompare> compares = new ArrayList<>();
        for (BoolExp operand : operands) {
            if (!(operand instanceof ColumnCompare compare)) {
                throw refuse(where, "a nested _and/_or/_not group cannot be enforced on MongoDB yet: "
                        + "use one flat _and or _or of column comparisons");
            }
            compares.add(compare);
        }
        return compares;
    }

    private static Rule rule(CollectionDef def, ColumnCompare compare, String where) {
        String field = CollectionDef.MONGO_ID.equals(compare.column()) ? ID : compare.column();
        if (!def.declares(field)) {
            throw refuse(where, "column \"" + compare.column() + "\" is not declared on " + def.name());
        }
        FieldKind kind = def.kindOf(field);
        FieldType type = ruleType(kind);
        ComparisonOperator operator = compare.operator();
        if (operator == ComparisonOperator.IS_NULL) {
            boolean wantsNull = booleanLiteral(compare, where, field);
            return new Rule(field, type, wantsNull ? RuleOperator.IS_NULL : RuleOperator.IS_NOT_NULL, null);
        }
        return new Rule(field, type, OPERATORS.get(operator), value(kind, field, compare, where));
    }

    private static boolean booleanLiteral(ColumnCompare compare, String where, String field) {
        if (compare.operand() instanceof Literal literal && literal.value() instanceof Boolean flag) {
            return flag;
        }
        throw refuse(where, "_is_null on \"" + field + "\" takes true or false");
    }

    private static String value(FieldKind kind, String field, ColumnCompare compare, String where) {
        boolean list = compare.operator() == ComparisonOperator.IN || compare.operator() == ComparisonOperator.NIN;
        if (compare.operand() instanceof SessionVariable variable) {
            if (list) {
                throw refuse(where, "_in/_nin with a session variable cannot be enforced on MongoDB yet");
            }
            return CLAIM_OPEN + variable.name() + CLAIM_CLOSE;
        }
        Object literal = ((Literal) compare.operand()).value();
        if (list) {
            return listValue(kind, field, literal, where);
        }
        return scalar(kind, field, literal, where);
    }

    private static String listValue(FieldKind kind, String field, Object literal, String where) {
        if (!(literal instanceof List<?> items) || items.isEmpty()) {
            throw refuse(where, "_in/_nin on \"" + field + "\" takes a non-empty list");
        }
        List<String> texts = new ArrayList<>();
        for (Object item : items) {
            String text = scalar(kind, field, item, where);
            if (text.startsWith(CLAIM_OPEN) || text.endsWith(CLAIM_CLOSE)) {
                throw refuse(where, "a list value on \"" + field + "\" starts or ends like a claim ({{...}}), "
                        + "which the joined list could turn into one");
            }
            if (text.contains(",")) {
                throw refuse(where, "a list value on \"" + field + "\" holds a comma, which the rule model would split");
            }
            if (!text.equals(text.strip()) || text.isEmpty()) {
                throw refuse(where, "a list value on \"" + field + "\" is empty or has a leading or trailing space");
            }
            texts.add(text);
        }
        return String.join(",", texts);
    }

    /** The literal as the text the rule model parses, only when its JSON type is the declared one. */
    private static String scalar(FieldKind kind, String field, Object literal, String where) {
        if (literal == null) {
            throw refuse(where, "a null literal on \"" + field + "\" never matches: use _is_null");
        }
        boolean fits = switch (kind) {
            case STRING, OBJECT_ID, DATETIME -> literal instanceof String;
            case INT, LONG -> literal instanceof Long || isWhole(literal);
            case DOUBLE -> literal instanceof Long || literal instanceof BigDecimal;
            case BOOLEAN -> literal instanceof Boolean;
        };
        if (!fits) {
            throw refuse(where, "the value on \"" + field + "\" is not a " + kind + ", which is how the field is declared");
        }
        String text = literal instanceof BigDecimal number ? number.toPlainString() : String.valueOf(literal);
        if (text.startsWith(CLAIM_OPEN) && text.endsWith(CLAIM_CLOSE)) {
            throw refuse(where, "the value on \"" + field + "\" looks like a claim ({{...}}); use a session variable");
        }
        return text;
    }

    private static boolean isWhole(Object literal) {
        return literal instanceof BigDecimal number && number.stripTrailingZeros().scale() <= 0;
    }

    private static FieldType ruleType(FieldKind kind) {
        return switch (kind) {
            case STRING, OBJECT_ID -> FieldType.STRING;
            case INT -> FieldType.INTEGER;
            case LONG -> FieldType.LONG;
            case DOUBLE -> FieldType.DOUBLE;
            case BOOLEAN -> FieldType.BOOLEAN;
            case DATETIME -> FieldType.DATETIME;
        };
    }

    private static Rule isNotNull(String field) {
        return new Rule(field, FieldType.STRING, RuleOperator.IS_NOT_NULL, null);
    }

    /** Default deny: a collection no role may read is governed by a policy that no document satisfies. */
    private static Policy matchNothing(CollectionDef def) {
        return new Policy("deny-" + def.name(), "no select permission on " + def.name(), def.name(), PolicyEffect.ALLOW,
                Set.of(Operation.SELECT), LogicOperator.AND, 0, true,
                List.of(new Rule(ID, FieldType.STRING, RuleOperator.IS_NULL, null), isNotNull(ID)),
                List.of(Assignment.all()));
    }

    private static IllegalStateException refuse(String where, String reason) {
        return new IllegalStateException(where + reason);
    }
}
