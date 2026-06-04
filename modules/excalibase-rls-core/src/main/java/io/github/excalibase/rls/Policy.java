package io.github.excalibase.rls;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Policy(
    String id,
    String name,
    String resource,
    PolicyEffect effect,
    Set<Operation> operations,
    LogicOperator ruleLogic,
    int priority,
    boolean enabled,
    List<Rule> rules,
    List<Assignment> assignments
) {
    public Policy {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(ruleLogic, "ruleLogic");
        operations = (operations == null || operations.isEmpty()) ? Operation.ALL : Set.copyOf(operations);
        rules = (rules == null) ? List.of() : List.copyOf(rules);
        assignments = (assignments == null) ? List.of() : List.copyOf(assignments);
    }

    public boolean appliesTo(Operation op) {
        return operations.contains(op);
    }
}
