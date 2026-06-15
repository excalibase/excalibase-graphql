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

        // ALLOW path: when no ALLOW exists for this resource and operation
        // anywhere, RLS is off and the row passes after the earlier DENY check.
        // Otherwise RLS is on
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
     * WITH-CHECK for an UPDATE's partial new image — {@code changedRow} holds
     * only the columns the caller is setting, not the full post-update row.
     *
     * <p>Native RLS evaluates WITH-CHECK over the complete new row; here we only
     * have the changed columns, so we enforce the half we can prove: a changed
     * column whose new value violates a policy is rejected. Rules that reference
     * columns the caller did not touch are treated as still satisfied, because
     * the UPDATE's USING predicate already validated the pre-update row for
     * those columns — this keeps legitimate partial updates working while still
     * blocking the real attack (reassigning an ownership/tenant column out of
     * policy). DENY policies veto on any changed value they match.
     *
     * @return {@code true} if the changed image is permitted (or no UPDATE
     *         policy exists for the resource); {@code false} if it must be rejected
     */
    public boolean matchesUpdate(String resource, Map<String, Object> changedRow, UserContext ctx) {
        VariableResolver resolver = new VariableResolver(ctx);
        List<Policy> applicable = inScope(resource, ctx, Operation.UPDATE);

        for (Policy p : applicable) {
            if (p.effect() == PolicyEffect.DENY
                    && referencesAnyChangedColumn(p, changedRow)
                    && matchesPresentRules(p, changedRow, resolver)) {
                return false;
            }
        }

        if (!rlsEnabledFor(resource, Operation.UPDATE)) return true;

        // The new image is permitted unless a changed column it actually sets
        // violates every ALLOW policy that governs that column. An ALLOW that
        // does not reference any changed column imposes no constraint here (its
        // columns are unchanged and already validated by USING).
        boolean anyAllowGovernsChange = false;
        for (Policy p : applicable) {
            if (p.effect() != PolicyEffect.ALLOW) continue;
            if (!referencesAnyChangedColumn(p, changedRow)) continue;
            anyAllowGovernsChange = true;
            if (matchesPresentRules(p, changedRow, resolver)) return true;
        }
        return !anyAllowGovernsChange;
    }

    /** True if any of the policy's rules targets a column present in {@code changedRow}. */
    private static boolean referencesAnyChangedColumn(Policy policy, Map<String, Object> changedRow) {
        for (Rule r : policy.rules()) {
            if (changedRow.containsKey(rootField(r.field()))) return true;
        }
        return false;
    }

    /**
     * Evaluates only the rules whose field is present in {@code changedRow},
     * honouring the policy's AND/OR logic. Rules on absent (unchanged) columns
     * are skipped — they are validated by the UPDATE's USING predicate, not here.
     */
    private static boolean matchesPresentRules(Policy policy, Map<String, Object> changedRow, VariableResolver resolver) {
        List<Rule> present = policy.rules().stream()
                .filter(r -> changedRow.containsKey(rootField(r.field())))
                .toList();
        if (present.isEmpty()) return true;
        if (policy.ruleLogic() == LogicOperator.AND) {
            for (Rule r : present) {
                if (!matchesRule(r, changedRow, resolver)) return false;
            }
            return true;
        }
        for (Rule r : present) {
            if (matchesRule(r, changedRow, resolver)) return true;
        }
        return false;
    }

    private static String rootField(String field) {
        int dot = field.indexOf('.');
        return dot < 0 ? field : field.substring(0, dot);
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
                catch (java.time.format.DateTimeParseException _) {
                    yield LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
                }
            }
        };
    }
}
