package io.github.excalibase.mongodb;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * A MongoDB match expression with its two constant cases kept apart, so combining never builds
 * an empty {@code $and} (an error) and a mandatory filter that matches nothing is recognisable.
 * The documents inside are never mutated after construction.
 */
public record Cond(Document doc, Kind kind) {

    public enum Kind { ALWAYS, NEVER, EXPR }

    public static final Cond ALWAYS = new Cond(new Document(), Kind.ALWAYS);
    public static final Cond NEVER = new Cond(new Document("_id", new Document("$in", List.of())), Kind.NEVER);

    public static Cond of(Document doc) {
        return new Cond(doc, Kind.EXPR);
    }

    public static Cond and(List<Cond> operands) {
        List<Document> docs = new ArrayList<>();
        for (Cond operand : operands) {
            if (operand.kind == Kind.NEVER) {
                return NEVER;
            }
            if (operand.kind == Kind.EXPR) {
                docs.add(operand.doc);
            }
        }
        return combine("$and", docs, ALWAYS);
    }

    public static Cond or(List<Cond> operands) {
        List<Document> docs = new ArrayList<>();
        for (Cond operand : operands) {
            if (operand.kind == Kind.ALWAYS) {
                return ALWAYS;
            }
            if (operand.kind == Kind.EXPR) {
                docs.add(operand.doc);
            }
        }
        return combine("$or", docs, NEVER);
    }

    private static Cond combine(String operator, List<Document> docs, Cond whenEmpty) {
        if (docs.isEmpty()) {
            return whenEmpty;
        }
        return docs.size() == 1 ? of(docs.getFirst()) : of(new Document(operator, docs));
    }
}
