package io.github.excalibase.mongodb;

import java.util.List;

/**
 * A predicate under SQL's three-valued logic, as two MongoDB expressions: {@code t} matches the documents
 * where it is true, {@code f} those where it is definitely false. A document in neither is "unknown"
 * (a NULL or missing field), and an unknown never passes: not an ALLOW, and not the negation of a DENY.
 * MongoDB's own {@code $ne}/{@code $not} are two-valued and would let those documents through.
 */
record Tri(Cond t, Cond f) {

    static final Tri UNKNOWN = new Tri(Cond.NEVER, Cond.NEVER);

    Tri not() {
        return new Tri(f, t);
    }

    static Tri and(List<Tri> operands) {
        return new Tri(Cond.and(operands.stream().map(Tri::t).toList()), Cond.or(operands.stream().map(Tri::f).toList()));
    }

    static Tri or(List<Tri> operands) {
        return new Tri(Cond.or(operands.stream().map(Tri::t).toList()), Cond.and(operands.stream().map(Tri::f).toList()));
    }
}
