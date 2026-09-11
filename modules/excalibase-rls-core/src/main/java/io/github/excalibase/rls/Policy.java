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
    List<RelationPredicate> relations,
    List<Assignment> assignments
) {
    public Policy {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(ruleLogic, "ruleLogic");
        operations = (operations == null || operations.isEmpty()) ? Operation.ALL : Set.copyOf(operations);
        rules = (rules == null) ? List.of() : List.copyOf(rules);
        relations = (relations == null) ? List.of() : List.copyOf(relations);
        assignments = (assignments == null) ? List.of() : List.copyOf(assignments);
    }

    /** Back-compatible constructor for policies without relationship predicates. */
    public Policy(String id, String name, String resource, PolicyEffect effect, Set<Operation> operations,
                  LogicOperator ruleLogic, int priority, boolean enabled,
                  List<Rule> rules, List<Assignment> assignments) {
        this(id, name, resource, effect, operations, ruleLogic, priority, enabled, rules, List.of(), assignments);
    }

    public boolean appliesTo(Operation op) {
        return operations.contains(op);
    }
}
