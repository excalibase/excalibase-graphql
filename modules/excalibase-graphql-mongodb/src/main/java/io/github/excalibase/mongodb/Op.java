package io.github.excalibase.mongodb;

import java.util.Arrays;
import java.util.Optional;

/** The filter operators a request may use. This is the whole vocabulary: nothing else reaches the database. */
public enum Op {
    EQ("eq"), NEQ("neq"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), IN("in"), NOT_IN("notIn"), IS_NULL("isNull");

    private final String graphqlName;

    Op(String graphqlName) {
        this.graphqlName = graphqlName;
    }

    public String graphqlName() {
        return graphqlName;
    }

    public static Optional<Op> fromGraphql(String name) {
        return Arrays.stream(values()).filter(op -> op.graphqlName.equals(name)).findFirst();
    }
}
