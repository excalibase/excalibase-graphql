package io.github.excalibase.rls;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class RowMatcher {

    private final List<Policy> policies;

    public RowMatcher(List<Policy> policies) {
        this.policies = (policies == null) ? List.of() : List.copyOf(policies);
    }

    public boolean matches(String resource, Map<String, Object> row, UserContext ctx, Operation op) {
        VariableResolver resolver = new VariableResolver(ctx);
        List<Policy> applicable = inScope(resource, ctx, op);

        // DENY always subtracts. A matching in-scope DENY hides the row whether
        // RLS is "on" for the resource or not — this supports the common
        // block-list pattern (a DENY without any ALLOW).
        for (Policy p : applicable) {
            if (p.effect() == PolicyEffect.DENY && matchesPolicy(p, row, resolver)) {
                return false;
            }
        }

        // ALLOW path: if no ALLOW exists for (resource, op) anywhere, RLS is off
        // and the row passes (DENY has already been checked). Otherwise RLS is on
        // and the subscriber needs at least one in-scope ALLOW that matches.
        if (!rlsEnabledFor(resource, op)) return true;

        for (Policy p : applicable) {
            if (p.effect() == PolicyEffect.ALLOW && matchesPolicy(p, row, resolver)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True iff at least one enabled ALLOW policy targets this resource and
     * declares this operation in its {@code operations} set. The "RLS is on"
     * test from RFC 0001's composition rule; computed without reference to the
     * subscriber.
     */
    private boolean rlsEnabledFor(String resource, Operation op) {
        for (Policy p : policies) {
            if (p.enabled()
                && p.effect() == PolicyEffect.ALLOW
                && p.resource().equals(resource)
                && p.appliesTo(op)) {
                return true;
            }
        }
        return false;
    }

    private List<Policy> inScope(String resource, UserContext ctx, Operation op) {
        Set<String> roles = ctx.roles() == null ? Set.of() : ctx.roles();
        Set<String> groups = ctx.groupIds() == null ? Set.of() : ctx.groupIds();
        String userId = ctx.userId();
        return policies.stream()
            .filter(Policy::enabled)
            .filter(p -> p.resource().equals(resource))
            .filter(p -> p.appliesTo(op))
            .filter(p -> assignmentMatches(p, userId, roles, groups))
            .toList();
    }

    private static boolean assignmentMatches(Policy p, String userId, Set<String> roles, Set<String> groups) {
        for (Assignment a : p.assignments()) {
            switch (a.targetType()) {
                case ALL: return true;
                case USER: if (userId != null && userId.equals(a.targetId())) return true; break;
                case ROLE: if (roles.contains(a.targetId())) return true; break;
                case GROUP: if (groups.contains(a.targetId())) return true; break;
            }
        }
        return false;
    }

    private static boolean matchesPolicy(Policy policy, Map<String, Object> row, VariableResolver resolver) {
        if (policy.rules().isEmpty()) return true;
        if (policy.ruleLogic() == LogicOperator.AND) {
            for (Rule r : policy.rules()) {
                if (!matchesRule(r, row, resolver)) return false;
            }
            return true;
        } else {
            for (Rule r : policy.rules()) {
                if (matchesRule(r, row, resolver)) return true;
            }
            return false;
        }
    }

    private static boolean matchesRule(Rule rule, Map<String, Object> row, VariableResolver resolver) {
        Object rowVal = readPath(row, rule.field());

        return switch (rule.operator()) {
            case IS_NULL -> rowVal == null;
            case IS_NOT_NULL -> rowVal != null;
            case EQ -> equalsCoerced(rowVal, resolver.resolve(rule.value(), rule.fieldType()), rule.fieldType());
            case NEQ -> !equalsCoerced(rowVal, resolver.resolve(rule.value(), rule.fieldType()), rule.fieldType());
            case IN -> inCollection(rowVal, resolver.resolveList(rule.value(), rule.fieldType()), rule.fieldType());
            case NOT_IN -> !inCollection(rowVal, resolver.resolveList(rule.value(), rule.fieldType()), rule.fieldType());
            case GT -> compareCoerced(rowVal, resolver.resolve(rule.value(), rule.fieldType()), rule.fieldType()) > 0;
            case GTE -> compareCoerced(rowVal, resolver.resolve(rule.value(), rule.fieldType()), rule.fieldType()) >= 0;
            case LT -> compareCoerced(rowVal, resolver.resolve(rule.value(), rule.fieldType()), rule.fieldType()) < 0;
            case LTE -> compareCoerced(rowVal, resolver.resolve(rule.value(), rule.fieldType()), rule.fieldType()) <= 0;
            case LIKE -> matchesLike(rowVal, (String) resolver.resolve(rule.value(), FieldType.STRING));
            case NOT_LIKE -> !matchesLike(rowVal, (String) resolver.resolve(rule.value(), FieldType.STRING));
        };
    }

    @SuppressWarnings("unchecked")
    private static Object readPath(Map<String, Object> row, String field) {
        if (!field.contains(".")) return row.get(field);
        String[] parts = field.split("\\.");
        Object current = row;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = ((Map<String, Object>) m).get(part);
            if (current == null) return null;
        }
        return current;
    }

    private static boolean equalsCoerced(Object rowVal, Object policyVal, FieldType fieldType) {
        if (rowVal == null || policyVal == null) return Objects.equals(rowVal, policyVal);
        return Objects.equals(coerce(rowVal, fieldType), coerce(policyVal, fieldType));
    }

    private static boolean inCollection(Object rowVal, Collection<?> values, FieldType fieldType) {
        if (rowVal == null) return false;
        Object coerced = coerce(rowVal, fieldType);
        for (Object v : values) {
            if (Objects.equals(coerced, coerce(v, fieldType))) return true;
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareCoerced(Object rowVal, Object policyVal, FieldType fieldType) {
        if (rowVal == null || policyVal == null) {
            throw new IllegalArgumentException("comparison operators require non-null operands");
        }
        Comparable left = (Comparable) coerce(rowVal, fieldType);
        Comparable right = (Comparable) coerce(policyVal, fieldType);
        return left.compareTo(right);
    }

    private static boolean matchesLike(Object rowVal, String pattern) {
        if (rowVal == null || pattern == null) return false;
        // Simple SQL-LIKE: % → .*, _ → . — sufficient for v1; document escape semantics later.
        String regex = "^" + pattern
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("%", ".*")
            .replace("_", ".")
            + "$";
        return rowVal.toString().matches(regex);
    }

    private static Object coerce(Object value, FieldType fieldType) {
        if (value == null) return null;
        return switch (fieldType) {
            case STRING -> value.toString();
            case UUID -> (value instanceof UUID) ? value : UUID.fromString(value.toString());
            case INTEGER -> (value instanceof Integer) ? value : Integer.parseInt(value.toString());
            case LONG -> (value instanceof Long) ? value : Long.parseLong(value.toString());
            case BOOLEAN -> (value instanceof Boolean) ? value : Boolean.parseBoolean(value.toString());
            case DOUBLE -> (value instanceof Double) ? value : Double.parseDouble(value.toString());
            case DECIMAL -> (value instanceof java.math.BigDecimal) ? value
                    : new java.math.BigDecimal(value.toString());
            case DATE -> (value instanceof LocalDate) ? value : LocalDate.parse(value.toString());
            case DATETIME -> {
                if (value instanceof Instant) yield value;
                if (value instanceof LocalDateTime ldt) yield ldt.toInstant(ZoneOffset.UTC);
                String s = value.toString();
                // Try ISO_INSTANT first (what Instant.toString() emits), then fall
                // back to LocalDateTime (no zone). Either is acceptable in rows.
                try { yield Instant.parse(s); }
                catch (java.time.format.DateTimeParseException ignored) {
                    yield LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
                }
            }
        };
    }
}
