package io.github.excalibase.mongodb;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.rls.UserContext;

import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Fixtures {
    private Fixtures() {}

    static CollectionDef orders() {
        Map<String, FieldKind> fields = new LinkedHashMap<>();
        fields.put("owner", FieldKind.STRING);
        fields.put("status", FieldKind.STRING);
        fields.put("total", FieldKind.INT);
        fields.put("paid", FieldKind.BOOLEAN);
        fields.put("placedAt", FieldKind.DATETIME);
        fields.put("secret", FieldKind.STRING);
        return new CollectionDef("orders", FieldKind.OBJECT_ID, fields);
    }

    static Catalog catalog() {
        return Catalog.of(orders());
    }

    /** The operators of a comparison on a scalar: arrays never match. */
    static Document scalar(String operator, Object value) {
        return new Document(operator, value).append("$not", new Document("$type", "array"));
    }

    static Rule rule(String field, RuleOperator operator, String value) {
        return new Rule(field, FieldType.STRING, operator, value);
    }

    static Policy policy(PolicyEffect effect, Rule... rules) {
        return policy("orders", effect, Operation.ALL, LogicOperator.AND, rules);
    }

    static Policy policy(String resource, PolicyEffect effect, Set<Operation> operations, LogicOperator logic,
                         Rule... rules) {
        return new Policy("p-" + effect, "p", resource, effect, operations, logic, 0, true,
                List.of(rules), List.of(Assignment.all()));
    }

    static UserContext caller(String userId) {
        return new UserContext() {
            @Override public String userId() { return userId; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
    }
}
