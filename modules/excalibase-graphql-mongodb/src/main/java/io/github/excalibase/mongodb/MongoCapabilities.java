package io.github.excalibase.mongodb;

import java.util.EnumSet;
import java.util.Set;

/**
 * What each database flavour supports, per field type. A small local equivalent of the dialect capability
 * API EXC-796 adds to the SQL engines: an operator not listed here is absent from the generated schema
 * and refused by the compiler, never approximated.
 */
public final class MongoCapabilities {

    private static final Set<Op> EQUALITY = EnumSet.of(Op.EQ, Op.NEQ, Op.IN, Op.NOT_IN, Op.IS_NULL);
    private static final Set<Op> ORDERED = EnumSet.allOf(Op.class);

    /** MongoDB and, so far as the integration tests show, DocumentDB: the same set. */
    public static final MongoCapabilities STANDARD = new MongoCapabilities();

    private MongoCapabilities() {
    }

    public boolean supports(FieldKind kind, Op op) {
        return operators(kind).contains(op);
    }

    public Set<Op> operators(FieldKind kind) {
        return switch (kind) {
            case BOOLEAN, OBJECT_ID -> EQUALITY;
            case STRING, INT, LONG, DOUBLE, DATETIME -> ORDERED;
        };
    }
}
