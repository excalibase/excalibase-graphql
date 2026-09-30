package io.github.excalibase.permissions;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.excalibase.permissions.BoolExp.And;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import io.github.excalibase.permissions.BoolExp.Exists;
import io.github.excalibase.permissions.BoolExp.Not;
import io.github.excalibase.permissions.BoolExp.Or;
import io.github.excalibase.permissions.BoolExp.Relationship;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.excalibase.permissions.JsonValues.fail;

/**
 * Reads a Hasura-style boolean expression (docs/features/permissions.md §4). Anything outside the
 * grammar, deeper than {@value #MAX_DEPTH} or larger than {@value #MAX_NODES} nodes is refused with
 * {@link PermissionDocumentException}; a missing expression is refused, never read as "every row".
 *
 * <p>An object whose keys are all comparison operators compares the column it is keyed by; any other
 * object under a name is a relationship. Whether a name is really a column or a relationship is
 * checked against the reflected schema when the access plan is built.
 */
public final class BoolExpParser {

    public static final int MAX_DEPTH = 16;
    public static final int MAX_NODES = 200;

    private static final Set<String> EXISTS_KEYS = Set.of("_table", "_where");

    private final String path;
    private int nodes;

    private BoolExpParser(String path) {
        this.path = path;
    }

    public static BoolExp parse(JsonNode node) {
        return parse(node, "expression");
    }

    /** @param path where the expression sits in its document, for the refusal message */
    public static BoolExp parse(JsonNode node, String path) {
        return new BoolExpParser(path).expression(node, 1);
    }

    private BoolExp expression(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            throw fail(path, "exceeds the maximum depth of " + MAX_DEPTH);
        }
        if (node == null || !node.isObject()) {
            throw fail(path, "an expression must be an object");
        }
        if (node.isEmpty()) {
            return counted(BoolExp.TRUE);
        }
        List<BoolExp> terms = new ArrayList<>();
        node.properties().forEach(entry -> terms.add(term(entry.getKey(), entry.getValue(), depth)));
        return terms.size() == 1 ? terms.getFirst() : counted(new And(terms));
    }

    private BoolExp term(String key, JsonNode value, int depth) {
        return switch (key) {
            case "_and" -> counted(new And(expressions(key, value, depth)));
            case "_or" -> counted(new Or(expressions(key, value, depth)));
            case "_not" -> counted(new Not(expression(value, depth + 1)));
            case "_exists" -> exists(value, depth);
            default -> named(key, value, depth);
        };
    }

    private BoolExp named(String name, JsonNode value, int depth) {
        if (ComparisonOperator.fromJsonName(name).isPresent()) {
            throw fail(path, "operator \"" + name + "\" must be keyed by a column");
        }
        if (!JsonValues.FIELD.matcher(name).matches()) {
            throw fail(path, "\"" + name + "\" is not a valid column or relationship name");
        }
        if (!value.isObject()) {
            throw fail(path, "\"" + name + "\" must map to an object");
        }
        if (isComparison(value)) {
            return comparisons(name, value);
        }
        return counted(new Relationship(name, expression(value, depth + 1)));
    }

    private static boolean isComparison(JsonNode value) {
        if (value.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            if (ComparisonOperator.fromJsonName(entry.getKey()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<BoolExp> expressions(String key, JsonNode value, int depth) {
        if (!value.isArray()) {
            throw fail(path, "\"" + key + "\" takes an array");
        }
        List<BoolExp> operands = new ArrayList<>();
        value.forEach(element -> operands.add(expression(element, depth + 1)));
        return operands;
    }

    private BoolExp exists(JsonNode value, int depth) {
        JsonValues.object(value, path + "._exists", EXISTS_KEYS);
        String table = JsonValues.matching(value, "_table", path + "._exists", JsonValues.QUALIFIED_NAME);
        if (!value.has("_where")) {
            throw fail(path + "._exists", "_where is required");
        }
        return counted(new Exists(table, expression(value.get("_where"), depth + 1)));
    }

    private BoolExp comparisons(String column, JsonNode operators) {
        List<BoolExp> compares = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : operators.properties()) {
            ComparisonOperator operator = ComparisonOperator.fromJsonName(entry.getKey()).orElseThrow();
            compares.add(counted(new ColumnCompare(column, operator, operand(column, operator, entry.getValue()))));
        }
        return compares.size() == 1 ? compares.getFirst() : counted(new And(compares));
    }

    private Operand operand(String column, ComparisonOperator operator, JsonNode value) {
        String where = "\"" + column + "\"." + operator.jsonName();
        return switch (operator) {
            case IS_NULL -> {
                if (!value.isBoolean()) {
                    throw fail(path, where + " takes true or false");
                }
                yield new Literal(value.booleanValue());
            }
            case IN, NIN -> listOperand(where, value);
            case LIKE, NLIKE -> {
                if (!value.isTextual()) {
                    throw fail(path, where + " takes a string");
                }
                yield textOperand(where, value.textValue());
            }
            default -> {
                if (!JsonValues.isScalar(value)) {
                    throw fail(path, where + " takes a string, number or boolean");
                }
                yield value.isTextual() ? textOperand(where, value.textValue()) : new Literal(JsonValues.plain(value));
            }
        };
    }

    private Operand listOperand(String where, JsonNode value) {
        if (value.isTextual() && SessionVariable.looksLikeVariable(value.textValue())) {
            return textOperand(where, value.textValue());
        }
        if (!value.isArray()) {
            throw fail(path, where + " takes an array or a session variable");
        }
        for (JsonNode element : value) {
            boolean variable = element.isTextual() && SessionVariable.looksLikeVariable(element.textValue());
            if (!JsonValues.isScalar(element) || variable) {
                throw fail(path, where + " takes an array of strings, numbers or booleans");
            }
        }
        return new Literal(JsonValues.plain(value));
    }

    private Operand textOperand(String where, String text) {
        if (!SessionVariable.looksLikeVariable(text)) {
            return new Literal(text);
        }
        if (!SessionVariable.isValidName(text)) {
            throw fail(path, where + " names an invalid session variable");
        }
        return new SessionVariable(text);
    }

    private BoolExp counted(BoolExp node) {
        nodes++;
        if (nodes > MAX_NODES) {
            throw fail(path, "has more than " + MAX_NODES + " nodes");
        }
        return node;
    }
}
