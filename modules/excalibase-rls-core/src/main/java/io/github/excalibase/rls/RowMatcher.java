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
        // A DENY that is TRUE or UNKNOWN hides the row, as NOT (deny) does in SQL.
        for (Policy p : applicable) {
            if (p.effect() == PolicyEffect.DENY && evalPolicy(p, row, resolver) != Truth.FALSE) {
                return false;
            }
        }

        // ALLOW path: when no ALLOW exists for this resource and operation
        // anywhere, RLS is off and the row passes after the earlier DENY check.
        // Otherwise RLS is on
        // and the subscriber needs at least one in-scope ALLOW that matches.
        if (!rlsEnabledFor(resource, op)) return true;

        for (Policy p : applicable) {
            if (p.effect() == PolicyEffect.ALLOW && evalPolicy(p, row, resolver) == Truth.TRUE) {
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
                    && evalPresentRules(p, changedRow, resolver) != Truth.FALSE) {
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
            if (p.effect() != PolicyEffect.ALLOW || !referencesAnyChangedColumn(p, changedRow)) continue;
            anyAllowGovernsChange = true;
            if (evalPresentRules(p, changedRow, resolver) == Truth.TRUE) return true;
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
    private static Truth evalPresentRules(Policy policy, Map<String, Object> changedRow, VariableResolver resolver) {
        List<Rule> present = policy.rules().stream()
                .filter(r -> changedRow.containsKey(rootField(r.field())))
                .toList();
        return evalRules(present, policy.ruleLogic(), changedRow, resolver);
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
                && ResourceMatcher.matches(p.resource(), resource)
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
            .filter(p -> ResourceMatcher.matches(p.resource(), resource))
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

    private static Truth evalPolicy(Policy policy, Map<String, Object> row, VariableResolver resolver) {
        if (!policy.relations().isEmpty()) {
            // Relationship (EXISTS) predicates probe another table — the in-memory
            // matcher has no database to probe. These are evaluated by the SQL
            // query path (JdbcEvaluator); realtime relation evaluation needs a DB
            // lookup and is a separate feature. Fail loud rather than silently
            // grant or hide a row on an unevaluable predicate.
            throw new UnsupportedOperationException(
                "Policy '" + policy.name() + "' uses relationship predicates, which the in-memory "
                    + "RowMatcher cannot evaluate; use the SQL query path (JdbcEvaluator).");
        }
        return evalRules(policy.rules(), policy.ruleLogic(), row, resolver);
    }

    private static Truth evalRules(List<Rule> rules, LogicOperator logic, Map<String, Object> row,
                                   VariableResolver resolver) {
        boolean and = logic == LogicOperator.AND;
        if (rules.isEmpty()) return Truth.TRUE;
        Truth result = and ? Truth.TRUE : Truth.FALSE;
        for (Rule r : rules) {
            Truth t = evalRule(r, row, resolver);
            result = and ? result.and(t) : result.or(t);
            if (result == (and ? Truth.FALSE : Truth.TRUE)) return result;
        }
        return result;
    }

    // A column missing from the image (key-only DELETE, unchanged TOAST value)
    // could hold anything, so a rule over it is UNKNOWN, like a NULL comparison.
    private static Truth evalRule(Rule rule, Map<String, Object> row, VariableResolver resolver) {
        if (!row.containsKey(rootField(rule.field()))) return Truth.UNKNOWN;
        Object rowVal = readPath(row, rule.field());

        return switch (rule.operator()) {
            case IS_NULL -> Truth.of(rowVal == null);
            case IS_NOT_NULL -> Truth.of(rowVal != null);
            case EQ -> equalsCoerced(rowVal, resolver.resolve(rule.value(), rule.fieldType()), rule.fieldType());
            case NEQ -> equalsCoerced(rowVal, resolver.resolve(rule.value(), rule.fieldType()), rule.fieldType()).not();
            case IN -> inCollection(rowVal, resolver.resolveList(rule.value(), rule.fieldType()), rule.fieldType());
            case NOT_IN -> inCollection(rowVal, resolver.resolveList(rule.value(), rule.fieldType()), rule.fieldType()).not();
            case GT, GTE, LT, LTE -> compare(rowVal, rule, resolver);
            case LIKE -> matchesLike(rowVal, (String) resolver.resolve(rule.value(), FieldType.STRING));
            case NOT_LIKE -> matchesLike(rowVal, (String) resolver.resolve(rule.value(), FieldType.STRING)).not();
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

    private static Truth equalsCoerced(Object rowVal, Object policyVal, FieldType fieldType) {
        if (rowVal == null || policyVal == null) return Truth.UNKNOWN;
        return Truth.of(Objects.equals(coerce(rowVal, fieldType), coerce(policyVal, fieldType)));
    }

    // Mirrors the emitted SQL: an empty list is 1=0 (IN) / 1=1 (NOT IN) whatever the row holds.
    private static Truth inCollection(Object rowVal, Collection<?> values, FieldType fieldType) {
        if (values.isEmpty()) return Truth.FALSE;
        if (rowVal == null) return Truth.UNKNOWN;
        Object coerced = coerce(rowVal, fieldType);
        boolean sawNull = false;
        for (Object v : values) {
            if (v == null) sawNull = true;
            else if (Objects.equals(coerced, coerce(v, fieldType))) return Truth.TRUE;
        }
        return sawNull ? Truth.UNKNOWN : Truth.FALSE;
    }

    /**
     * GT/GTE/LT/LTE with SQL three-valued logic: a comparison involving NULL
     * yields UNKNOWN, so the row is excluded — it never throws. This keeps the in-memory matcher in lockstep with the emitted SQL
     * (and native Postgres), which simply drop the row.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Truth compare(Object rowVal, Rule rule, VariableResolver resolver) {
        Object policyVal = resolver.resolve(rule.value(), rule.fieldType());
        if (rowVal == null || policyVal == null) return Truth.UNKNOWN;
        Comparable left = (Comparable) coerce(rowVal, rule.fieldType());
        Comparable right = (Comparable) coerce(policyVal, rule.fieldType());
        // Coercion can yield null for an unparseable value → uncomparable, so the
        // row is excluded (SQL three-valued logic) rather than throwing on compareTo.
        if (left == null || right == null) return Truth.UNKNOWN;
        int c = left.compareTo(right);
        return Truth.of(switch (rule.operator()) {
            case GT -> c > 0;
            case GTE -> c >= 0;
            case LT -> c < 0;
            case LTE -> c <= 0;
            default -> throw new IllegalStateException("compare() called for non-comparison operator " + rule.operator());
        });
    }

    private static Truth matchesLike(Object rowVal, String pattern) {
        if (rowVal == null || pattern == null) return Truth.UNKNOWN;
        // Simple SQL-LIKE: % → .*, _ → . — sufficient for v1; document escape semantics later.
        String regex = "^" + pattern
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("%", ".*")
            .replace("_", ".")
            + "$";
        return Truth.of(rowVal.toString().matches(regex));
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

    /** SQL three-valued logic (Kleene): a row is kept only when its predicate is TRUE. */
    private enum Truth {
        TRUE, FALSE, UNKNOWN;

        static Truth of(boolean value) {
            return value ? TRUE : FALSE;
        }

        Truth not() {
            if (this == UNKNOWN) return UNKNOWN;
            return this == TRUE ? FALSE : TRUE;
        }

        Truth and(Truth other) {
            if (this == FALSE || other == FALSE) return FALSE;
            return (this == TRUE && other == TRUE) ? TRUE : UNKNOWN;
        }

        Truth or(Truth other) {
            if (this == TRUE || other == TRUE) return TRUE;
            return (this == FALSE && other == FALSE) ? FALSE : UNKNOWN;
        }
    }
}
