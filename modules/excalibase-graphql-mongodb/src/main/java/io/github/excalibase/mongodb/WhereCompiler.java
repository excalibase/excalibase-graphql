package io.github.excalibase.mongodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a request's {@code where} argument. The vocabulary is closed: field names must be declared and
 * visible to the caller, operators come from {@link Op}, and values pass through {@link ValueCodec}, so no
 * part of the input can become a MongoDB operator, a path or an expression.
 */
final class WhereCompiler {

    static final int MAX_LIST = 1000;

    private final MongoCapabilities capabilities;

    WhereCompiler(MongoCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    /** @param visible the fields (GraphQL names, {@code id} included) the caller may filter by */
    Cond compile(CollectionDef collection, Set<String> visible, Map<String, Object> where) {
        List<Cond> terms = new ArrayList<>();
        for (Map.Entry<String, Object> entry : where.entrySet()) {
            String field = entry.getKey();
            if (!collection.declares(field) || !visible.contains(field)) {
                throw new RequestException("Unknown field \"" + field + "\" on " + collection.name());
            }
            if (!(entry.getValue() instanceof Map<?, ?> operators)) {
                throw new RequestException("The filter for \"" + field + "\" must be an object of operators");
            }
            for (Map.Entry<?, ?> operator : operators.entrySet()) {
                terms.add(term(collection, field, String.valueOf(operator.getKey()), operator.getValue()).t());
            }
        }
        return Cond.and(terms);
    }

    private Tri term(CollectionDef collection, String field, String operatorName, Object value) {
        Op op = Op.fromGraphql(operatorName)
                .orElseThrow(() -> new RequestException("Unknown operator \"" + operatorName + "\""));
        FieldKind kind = collection.kindOf(field);
        if (!capabilities.supports(kind, op)) {
            throw new RequestException("Operator \"" + operatorName + "\" is not supported on " + kind
                    + " field \"" + field + "\"");
        }
        String path = CollectionDef.storedName(field);
        return switch (op) {
            case IS_NULL -> Predicates.isNull(path, flag(field, value));
            case IN, NOT_IN -> Predicates.member(path, kind, list(kind, field, value), op == Op.IN);
            default -> Predicates.compare(path, kind, op, scalar(kind, field, operatorName, value));
        };
    }

    private static boolean flag(String field, Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new RequestException("isNull on \"" + field + "\" takes true or false");
    }

    private static List<Object> list(FieldKind kind, String field, Object value) {
        if (!(value instanceof List<?> items)) {
            throw new RequestException("The list for \"" + field + "\" must be a list");
        }
        if (items.size() > MAX_LIST) {
            throw new RequestException("A list may hold at most " + MAX_LIST + " values");
        }
        List<Object> converted = new ArrayList<>(items.size());
        for (Object item : items) {
            converted.add(scalar(kind, field, "in", item));
        }
        return converted;
    }

    private static Object scalar(FieldKind kind, String field, String operator, Object value) {
        if (value == null) {
            throw new RequestException("\"" + operator + "\" on \"" + field + "\" takes a value: use isNull to test for null");
        }
        if (CollectionDef.ID_FIELD.equals(field)) {
            return value instanceof String text ? ValueCodec.parseId(kind, text) : ValueCodec.toBson(kind, value, field);
        }
        return ValueCodec.toBson(kind, value, field);
    }
}
