package io.github.excalibase.mongodb;

import org.bson.BsonRegularExpression;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the true/false expression pair of one comparison. Every value is a bound literal, never parsed.
 *
 * <p>A declared field is a scalar. MongoDB matches an array field element by element and compares across
 * types with {@code $ne}, which would let a document that drifted from its declaration slip through a
 * DENY or satisfy an ALLOW. So a comparison is true only on a scalar, and "definitely false" also needs the
 * stored value to be of the declared type: anything else is unknown, and unknown never passes.
 */
final class Predicates {

    private static final String TYPE = "$type";
    private static final Document NOT_AN_ARRAY = new Document(TYPE, "array");

    private Predicates() {}

    static Tri compare(String path, FieldKind kind, Op op, Object value) {
        return switch (op) {
            case EQ -> sameAs(path, kind, value);
            case NEQ -> sameAs(path, kind, value).not();
            case GT -> range(path, value, "$gt", "$lte");
            case GTE -> range(path, value, "$gte", "$lt");
            case LT -> range(path, value, "$lt", "$gte");
            case LTE -> range(path, value, "$lte", "$gt");
            default -> throw new IllegalArgumentException(op + " takes a list or a flag");
        };
    }

    static Tri member(String path, FieldKind kind, List<?> values, boolean positive) {
        Tri in = membership(path, kind, values);
        return positive ? in : in.not();
    }

    static Tri isNull(String path, boolean wanted) {
        Tri nullish = new Tri(field(path, new Document("$eq", null)),
                Cond.of(new Document(path, new Document("$ne", null))));
        return wanted ? nullish : nullish.not();
    }

    /** {@code LIKE}: {@code %} any run, {@code _} one character, backslash escapes; anchored, newlines included. */
    static Tri like(String path, String pattern, boolean positive) {
        if (pattern == null) {
            return Tri.UNKNOWN;
        }
        String regex = likeToRegex(pattern);
        Tri matches = new Tri(field(path, new Document("$regex", regex).append("$options", "s")),
                Cond.and(List.of(Cond.of(new Document(path, new Document(TYPE, "string"))),
                        Cond.of(new Document(path, new Document("$not", NOT_AN_ARRAY))),
                        Cond.of(new Document(path, new Document("$not", new BsonRegularExpression(regex, "s")))))));
        return positive ? matches : matches.not();
    }

    static String likeToRegex(String pattern) {
        StringBuilder regex = new StringBuilder("\\A");
        StringBuilder literal = new StringBuilder();
        boolean escaped = false;
        for (char c : pattern.toCharArray()) {
            if (escaped) {
                literal.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '%' || c == '_') {
                flush(regex, literal);
                regex.append(c == '%' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (escaped) {
            literal.append('\\');
        }
        flush(regex, literal);
        return regex.append("\\z").toString();
    }

    private static void flush(StringBuilder regex, StringBuilder literal) {
        if (!literal.isEmpty()) {
            // \Q..\E ends at the first \E, so a literal containing one is split around it
            regex.append("\\Q").append(literal.toString().replace("\\E", "\\E\\\\E\\Q")).append("\\E");
            literal.setLength(0);
        }
    }

    /** A condition on a scalar value of the field: the operators apply, and an array never matches. */
    private static Cond field(String path, Document operators) {
        Document scalar = new Document(operators);
        scalar.put("$not", NOT_AN_ARRAY);
        return Cond.of(new Document(path, scalar));
    }

    /** Definitely not {@code operators}: the field holds a scalar of the declared type that fails them. */
    private static Cond ofTypeAnd(String path, FieldKind kind, Document operators) {
        Document typed = new Document(TYPE, typeName(kind));
        typed.putAll(operators);
        typed.put("$not", NOT_AN_ARRAY);
        return Cond.of(new Document(path, typed));
    }

    private static String typeName(FieldKind kind) {
        return switch (kind) {
            case STRING -> "string";
            case OBJECT_ID -> "objectId";
            case INT, LONG, DOUBLE -> "number";
            case BOOLEAN -> "bool";
            case DATETIME -> "date";
        };
    }

    private static Tri sameAs(String path, FieldKind kind, Object value) {
        if (value == null) {
            return Tri.UNKNOWN;
        }
        return new Tri(field(path, new Document("$eq", value)), ofTypeAnd(path, kind, new Document("$ne", value)));
    }

    private static Tri range(String path, Object value, String holds, String fails) {
        if (value == null) {
            return Tri.UNKNOWN;
        }
        return new Tri(field(path, new Document(holds, value)), field(path, new Document(fails, value)));
    }

    /** SQL: an empty IN is false, whatever the column holds; a NULL in the list can make a miss "unknown". */
    private static Tri membership(String path, FieldKind kind, List<?> values) {
        if (values.isEmpty()) {
            return new Tri(Cond.NEVER, Cond.ALWAYS);
        }
        List<Object> present = new ArrayList<>(values.stream().filter(Objects::nonNull).toList());
        boolean sawNull = present.size() != values.size();
        Cond holds = present.isEmpty() ? Cond.NEVER : field(path, new Document("$in", present));
        if (sawNull) {
            return new Tri(holds, Cond.NEVER);
        }
        return new Tri(holds, ofTypeAnd(path, kind, new Document("$nin", present)));
    }
}
