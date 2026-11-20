package io.github.excalibase.mongodb;

import org.bson.BsonRegularExpression;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds the true/false expression pair of one comparison. Every value is a bound literal, never parsed. */
final class Predicates {

    private Predicates() {}

    static Tri compare(String path, Op op, Object value) {
        return switch (op) {
            case EQ -> equal(path, value);
            case NEQ -> equal(path, value).not();
            case GT -> range(path, value, "$gt", "$lte");
            case GTE -> range(path, value, "$gte", "$lt");
            case LT -> range(path, value, "$lt", "$gte");
            case LTE -> range(path, value, "$lte", "$gt");
            default -> throw new IllegalArgumentException(op + " takes a list or a flag");
        };
    }

    static Tri member(String path, List<?> values, boolean positive) {
        Tri in = membership(path, values);
        return positive ? in : in.not();
    }

    static Tri isNull(String path, boolean wanted) {
        Tri nullish = new Tri(Cond.of(new Document(path, (Object) null)),
                Cond.of(new Document(path, new Document("$ne", null))));
        return wanted ? nullish : nullish.not();
    }

    /** {@code LIKE}: {@code %} any run, {@code _} one character, backslash escapes; anchored, newlines included. */
    static Tri like(String path, String pattern, boolean positive) {
        if (pattern == null) {
            return Tri.UNKNOWN;
        }
        BsonRegularExpression regex = new BsonRegularExpression(likeToRegex(pattern), "s");
        Tri matches = new Tri(Cond.of(new Document(path, regex)),
                Cond.and(List.of(Cond.of(new Document(path, new Document("$type", "string"))),
                        Cond.of(new Document(path, new Document("$not", regex))))));
        return positive ? matches : matches.not();
    }

    static String likeToRegex(String pattern) {
        StringBuilder regex = new StringBuilder("\\A");
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            boolean wildcard = c == '%' || c == '_';
            if (c == '\\' && i + 1 < pattern.length()) {
                literal.append(pattern.charAt(++i));
            } else if (wildcard) {
                flush(regex, literal);
                regex.append(c == '%' ? ".*" : ".");
            } else {
                literal.append(c);
            }
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

    private static Tri equal(String path, Object value) {
        if (value == null) {
            return Tri.UNKNOWN;
        }
        return new Tri(Cond.of(new Document(path, new Document("$eq", value))), differs(path, value));
    }

    private static Cond differs(String path, Object value) {
        return Cond.and(List.of(notNull(path), Cond.of(new Document(path, new Document("$ne", value)))));
    }

    private static Cond notNull(String path) {
        return Cond.of(new Document(path, new Document("$ne", null)));
    }

    private static Tri range(String path, Object value, String holds, String fails) {
        if (value == null) {
            return Tri.UNKNOWN;
        }
        return new Tri(Cond.of(new Document(path, new Document(holds, value))),
                Cond.of(new Document(path, new Document(fails, value))));
    }

    /** SQL: an empty IN is false, whatever the column holds; a NULL in the list can make a miss "unknown". */
    private static Tri membership(String path, List<?> values) {
        if (values.isEmpty()) {
            return new Tri(Cond.NEVER, Cond.ALWAYS);
        }
        List<Object> present = new ArrayList<>(values.stream().filter(Objects::nonNull).toList());
        boolean sawNull = present.size() != values.size();
        Cond holds = present.isEmpty() ? Cond.NEVER : Cond.of(new Document(path, new Document("$in", present)));
        if (sawNull) {
            return new Tri(holds, Cond.NEVER);
        }
        return new Tri(holds, Cond.and(List.of(notNull(path),
                Cond.of(new Document(path, new Document("$nin", present))))));
    }
}
