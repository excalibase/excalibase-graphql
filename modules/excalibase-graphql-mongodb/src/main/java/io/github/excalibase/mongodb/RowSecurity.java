package io.github.excalibase.mongodb;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.ResourceMatcher;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.rls.UserContext;
import io.github.excalibase.rls.VariableResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import io.github.excalibase.rls.FieldType;

/**
 * Row-level security for MongoDB, from the same {@link Policy} model the SQL engines use. It composes the
 * way {@code JdbcEvaluator} does: RLS is on for a collection and operation when an enabled ALLOW policy
 * targets it; DENY always subtracts; an UPDATE or DELETE also needs the row to be readable. The result is a
 * match expression the pipeline compiler places first, so it is mandatory by construction.
 *
 * <p>Policies are checked when the engine starts. Whatever cannot be enforced exactly (relationship
 * predicates, nested fields, an undeclared field, a value of the wrong type) refuses to start, never
 * degrades into a weaker filter.
 */
final class RowSecurity {

    private final Catalog catalog;
    private final List<Policy> policies;

    RowSecurity(Catalog catalog, List<Policy> policies) {
        this.catalog = catalog;
        this.policies = List.copyOf(policies);
        this.policies.forEach(this::validate);
    }

    /** A policy that names no declared collection would never apply, and a mistyped DENY would fail open. */
    static void requireDeclared(Catalog catalog, String kind, String policy, String resource) {
        if (catalog.all().stream().noneMatch(def -> ResourceMatcher.matches(resource, def.name()))) {
            throw new IllegalStateException(kind + " \"" + policy + "\" targets \"" + resource
                    + "\", which is not a declared collection");
        }
    }

    /** True when an enabled ALLOW policy governs reads of the collection. */
    boolean governs(String collection) {
        return rlsOn(collection, Operation.SELECT);
    }

    Cond filter(String collection, UserContext caller, Operation operation) {
        CollectionDef def = catalog.collection(collection)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection " + collection));
        VariableResolver resolver = new VariableResolver(caller);
        Cond base = filterFor(def, caller, operation, resolver);
        if (operation == Operation.UPDATE || operation == Operation.DELETE) {
            return Cond.and(List.of(base, filterFor(def, caller, Operation.SELECT, resolver)));
        }
        return base;
    }

    private Cond filterFor(CollectionDef def, UserContext caller, Operation operation, VariableResolver resolver) {
        List<Tri> allows = new ArrayList<>();
        List<Tri> denies = new ArrayList<>();
        for (Policy policy : inScope(def.name(), caller, operation)) {
            Tri compiled = policy(def, policy, resolver);
            if (compiled == null) {
                continue;
            }
            (policy.effect() == PolicyEffect.ALLOW ? allows : denies).add(compiled);
        }
        // NOT (d1 OR d2) is true only where every deny is definitely false
        Cond notDenied = Cond.and(denies.stream().map(Tri::f).toList());
        if (!rlsOn(def.name(), operation)) {
            return notDenied;
        }
        if (allows.isEmpty()) {
            return Cond.NEVER;
        }
        return Cond.and(List.of(Cond.or(allows.stream().map(Tri::t).toList()), notDenied));
    }

    /** An empty policy is skipped, as the SQL evaluator skips it: it neither allows nor denies. */
    private Tri policy(CollectionDef def, Policy policy, VariableResolver resolver) {
        if (policy.rules().isEmpty()) {
            return null;
        }
        List<Tri> rules = policy.rules().stream().map(rule -> rule(def, rule, resolver)).toList();
        return policy.ruleLogic() == LogicOperator.AND ? Tri.and(rules) : Tri.or(rules);
    }

    private static Tri rule(CollectionDef def, Rule rule, VariableResolver resolver) {
        String field = fieldName(rule.field());
        FieldKind kind = def.kindOf(field);
        String path = CollectionDef.storedName(field);
        return switch (rule.operator()) {
            case IS_NULL -> Predicates.isNull(path, true);
            case IS_NOT_NULL -> Predicates.isNull(path, false);
            case EQ -> Predicates.compare(path, kind, Op.EQ, value(kind, field, rule, resolver));
            case NEQ -> Predicates.compare(path, kind, Op.NEQ, value(kind, field, rule, resolver));
            case GT -> Predicates.compare(path, kind, Op.GT, value(kind, field, rule, resolver));
            case GTE -> Predicates.compare(path, kind, Op.GTE, value(kind, field, rule, resolver));
            case LT -> Predicates.compare(path, kind, Op.LT, value(kind, field, rule, resolver));
            case LTE -> Predicates.compare(path, kind, Op.LTE, value(kind, field, rule, resolver));
            case IN -> Predicates.member(path, kind, values(kind, field, rule, resolver), true);
            case NOT_IN -> Predicates.member(path, kind, values(kind, field, rule, resolver), false);
            case LIKE -> Predicates.like(path, (String) resolver.resolve(rule.value(), FieldType.STRING), true);
            case NOT_LIKE -> Predicates.like(path, (String) resolver.resolve(rule.value(), FieldType.STRING), false);
        };
    }

    private static Object value(FieldKind kind, String field, Rule rule, VariableResolver resolver) {
        return policyValue(kind, field, resolver.resolve(rule.value(), rule.fieldType()));
    }

    private static List<Object> values(FieldKind kind, String field, Rule rule, VariableResolver resolver) {
        List<Object> converted = new ArrayList<>();
        for (Object item : resolver.resolveList(rule.value(), rule.fieldType())) {
            converted.add(policyValue(kind, field, item));
        }
        return converted;
    }

    /**
     * A caller's claim that cannot be the declared type is the server's problem, not a request the caller can
     * fix: it must not surface a {@link RequestException}, whose message names the policy's field to the caller.
     */
    private static Object policyValue(FieldKind kind, String field, Object resolved) {
        try {
            return ValueCodec.toBson(kind, resolved, field);
        } catch (RequestException e) {
            throw new IllegalStateException("A claim cannot be applied to the permission on \"" + field + "\": "
                    + e.getMessage(), e);
        }
    }

    /** {@code _id} and {@code id} are the same field; anything else is taken as written. */
    private static String fieldName(String field) {
        return CollectionDef.MONGO_ID.equals(field) ? CollectionDef.ID_FIELD : field;
    }

    private boolean rlsOn(String collection, Operation operation) {
        return policies.stream().anyMatch(p -> p.enabled() && p.effect() == PolicyEffect.ALLOW
                && ResourceMatcher.matches(p.resource(), collection) && p.appliesTo(operation));
    }

    private List<Policy> inScope(String collection, UserContext caller, Operation operation) {
        Set<String> roles = caller.roles() == null ? Set.of() : caller.roles();
        Set<String> groups = caller.groupIds() == null ? Set.of() : caller.groupIds();
        return policies.stream()
                .filter(Policy::enabled)
                .filter(p -> ResourceMatcher.matches(p.resource(), collection))
                .filter(p -> p.appliesTo(operation))
                .filter(p -> assigned(p, caller.userId(), roles, groups))
                .toList();
    }

    private static boolean assigned(Policy policy, String userId, Set<String> roles, Set<String> groups) {
        for (Assignment assignment : policy.assignments()) {
            boolean match = switch (assignment.targetType()) {
                case ALL -> true;
                case USER -> userId != null && userId.equals(assignment.targetId());
                case ROLE -> roles.contains(assignment.targetId());
                case GROUP -> groups.contains(assignment.targetId());
            };
            if (match) {
                return true;
            }
        }
        return false;
    }

    // ---- startup validation ----

    private void validate(Policy policy) {
        requireDeclared(catalog, "Policy", policy.name(), policy.resource());
        for (CollectionDef def : catalog.all()) {
            if (!ResourceMatcher.matches(policy.resource(), def.name())) {
                continue;
            }
            if (!policy.relations().isEmpty()) {
                throw refuse(policy, "uses relationship predicates, which cannot be enforced on MongoDB yet");
            }
            policy.rules().forEach(rule -> validate(policy, def, rule));
        }
    }

    private static void validate(Policy policy, CollectionDef def, Rule rule) {
        String field = fieldName(rule.field());
        if (!def.declares(field)) {
            throw refuse(policy, "rule on \"" + rule.field() + "\", which " + def.name()
                    + " does not declare (nested fields are not supported)");
        }
        FieldKind kind = def.kindOf(field);
        RuleOperator operator = rule.operator();
        boolean text = operator == RuleOperator.LIKE || operator == RuleOperator.NOT_LIKE;
        if (text && kind != FieldKind.STRING) {
            throw refuse(policy, operator + " needs a string field, but \"" + field + "\" is " + kind);
        }
        boolean ordered = operator == RuleOperator.GT || operator == RuleOperator.GTE
                || operator == RuleOperator.LT || operator == RuleOperator.LTE;
        if (ordered && !MongoCapabilities.STANDARD.supports(kind, Op.GT)) {
            throw refuse(policy, operator + " is not supported on " + kind + " field \"" + field + "\"");
        }
        if (!text && operator != RuleOperator.IS_NULL && operator != RuleOperator.IS_NOT_NULL
                && !kind.accepts(rule.fieldType())) {
            throw refuse(policy, "rule on \"" + field + "\" is declared " + rule.fieldType() + " but the field is " + kind);
        }
        if (rule.value() != null && !rule.value().startsWith("{{") && !text && !isNullTest(operator)) {
            checkLiteral(policy, kind, field, rule);
        }
    }

    private static boolean isNullTest(RuleOperator operator) {
        return operator == RuleOperator.IS_NULL || operator == RuleOperator.IS_NOT_NULL;
    }

    private static void checkLiteral(Policy policy, FieldKind kind, String field, Rule rule) {
        try {
            VariableResolver literal = new VariableResolver(null);
            boolean list = rule.operator() == RuleOperator.IN || rule.operator() == RuleOperator.NOT_IN;
            if (list) {
                literal.resolveList(rule.value(), rule.fieldType()).forEach(v -> ValueCodec.toBson(kind, v, field));
            } else {
                ValueCodec.toBson(kind, literal.resolve(rule.value(), rule.fieldType()), field);
            }
        } catch (RuntimeException _) {
            throw refuse(policy, "value of the rule on \"" + field + "\" is not a valid " + kind);
        }
    }

    private static IllegalStateException refuse(Policy policy, String reason) {
        return new IllegalStateException("Policy \"" + policy.name() + "\" " + reason);
    }
}
