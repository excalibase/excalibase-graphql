package io.github.excalibase.rls.jdbc;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.ColumnMasker;
import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.MaskMode;
import io.github.excalibase.rls.MaskingPlan;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.UserContext;
import io.github.excalibase.rls.VariableResolver;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a list of policies into a parameterized SQL fragment via
 * {@link #compile(String, UserContext, Operation)}. The fragment is meant to
 * be {@code AND}'d into the caller's own {@code WHERE} clause and run through
 * Spring's {@code NamedParameterJdbcTemplate} (or any equivalent that accepts
 * a {@code Map<String, Object>} of bind variables).
 *
 * <p>Composition mirrors {@link io.github.excalibase.rls.RowMatcher} and
 * RFC 0001's composition rule:
 * <ul>
 *   <li>RLS is "on" for {@code (resource, op)} iff at least one enabled
 *       ALLOW policy targets it.</li>
 *   <li>DENY policies always subtract — {@code NOT (OR(denies))} {@code AND}'d
 *       onto whatever the ALLOW side produces.</li>
 *   <li>RLS on with no in-scope ALLOW → {@link SqlFilter#DENY_ALL}.</li>
 *   <li>RLS off with no DENY → {@link SqlFilter#UNRESTRICTED}.</li>
 * </ul>
 */
public class JdbcEvaluator {

    private final List<Policy> policies;
    private final ColumnMasker columnMasker;

    public JdbcEvaluator(List<Policy> policies) {
        this(policies, List.of());
    }

    public JdbcEvaluator(List<Policy> policies, List<ColumnPolicy> columnPolicies) {
        this.policies = (policies == null) ? List.of() : List.copyOf(policies);
        this.columnMasker = new ColumnMasker(columnPolicies);
    }

    public SqlFilter compile(String resource, UserContext ctx, Operation op) {
        VariableResolver resolver = new VariableResolver(ctx);
        List<Policy> inScope = inScope(resource, ctx, op);

        ParamSink sink = new ParamSink();
        List<String> allowSqls = new ArrayList<>();
        List<String> denySqls = new ArrayList<>();

        for (Policy p : inScope) {
            String pSql = renderPolicy(p, resolver, sink);
            if (pSql == null) continue;
            if (p.effect() == PolicyEffect.ALLOW) allowSqls.add(pSql);
            else denySqls.add(pSql);
        }

        boolean rlsOn = rlsEnabledFor(resource, op);
        String denyClause = denySqls.isEmpty() ? null : "NOT (" + joinOr(denySqls) + ")";

        if (!rlsOn) {
            return denyClause == null ? SqlFilter.UNRESTRICTED : new SqlFilter(denyClause, sink.snapshot());
        }

        if (allowSqls.isEmpty()) return SqlFilter.DENY_ALL;

        String allowClause = joinOr(allowSqls);
        String sql = denyClause == null ? allowClause : "(" + allowClause + ") AND " + denyClause;
        return new SqlFilter(sql, sink.snapshot());
    }

    /**
     * Build the SELECT projection for {@code requestedColumns} per the
     * column policies in scope for this user + operation. HIDE columns are
     * dropped from the list (and reported in {@link SqlProjection#hidden()});
     * NULL columns are emitted as {@code "NULL AS \"col\""}; everything else
     * passes through quoted. v1.6 throws on PARTIAL/HASH/CUSTOM — see RFC 0007.
     */
    public SqlProjection project(String resource, UserContext ctx, Operation op,
                                  List<String> requestedColumns) {
        MaskingPlan plan = columnMasker.plan(resource, ctx, op);

        List<String> selectList = new ArrayList<>(requestedColumns.size());
        for (String requested : requestedColumns) {
            String safe = SqlIdentifier.checkColumn(requested);
            if (plan.hidden().contains(safe)) continue;
            MaskMode mode = plan.masked().get(safe);
            if (mode == null) {
                selectList.add(SqlIdentifier.quote(safe));
            } else {
                selectList.add(renderMaskedColumn(safe, mode, plan));
            }
        }
        return new SqlProjection(selectList, Map.of(), Set.copyOf(plan.hidden()));
    }

    private static String renderMaskedColumn(String column, MaskMode mode, MaskingPlan plan) {
        return switch (mode) {
            case NULL -> "NULL AS " + SqlIdentifier.quote(column);
            case PARTIAL -> throw new UnsupportedOperationException(
                "MaskMode PARTIAL SQL emission not implemented in v1.6 (RFC 0007 v1.7)");
            case HASH -> throw new UnsupportedOperationException(
                "MaskMode HASH SQL emission not implemented in v1.6 (RFC 0007 v1.7 ships dialect-aware emit)");
            case CUSTOM -> throw new UnsupportedOperationException(
                "MaskMode CUSTOM SQL emission not implemented in v1.6 (RFC 0007 v1.7)");
            case HIDE -> throw new IllegalStateException("HIDE columns should be dropped, not rendered");
        };
    }

    // ---------- composition helpers ----------

    private boolean rlsEnabledFor(String resource, Operation op) {
        for (Policy p : policies) {
            if (p.enabled()
                && p.effect() == PolicyEffect.ALLOW
                && p.resource().equals(resource)
                && p.appliesTo(op)) return true;
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

    private static String renderPolicy(Policy policy, VariableResolver resolver, ParamSink sink) {
        if (policy.rules().isEmpty()) return null;
        List<String> ruleSqls = new ArrayList<>(policy.rules().size());
        for (Rule r : policy.rules()) {
            ruleSqls.add(renderRule(r, resolver, sink));
        }
        if (ruleSqls.size() == 1) return ruleSqls.get(0);
        String joiner = policy.ruleLogic() == LogicOperator.AND ? " AND " : " OR ";
        return "(" + String.join(joiner, ruleSqls) + ")";
    }

    private static String renderRule(Rule rule, VariableResolver resolver, ParamSink sink) {
        String col = SqlIdentifier.quote(SqlIdentifier.checkColumn(rule.field()));

        return switch (rule.operator()) {
            case IS_NULL -> col + " IS NULL";
            case IS_NOT_NULL -> col + " IS NOT NULL";
            case EQ -> col + " = :" + sink.bind(resolver.resolve(rule.value(), rule.fieldType()));
            case NEQ -> col + " <> :" + sink.bind(resolver.resolve(rule.value(), rule.fieldType()));
            case GT -> col + " > :" + sink.bind(resolver.resolve(rule.value(), rule.fieldType()));
            case GTE -> col + " >= :" + sink.bind(resolver.resolve(rule.value(), rule.fieldType()));
            case LT -> col + " < :" + sink.bind(resolver.resolve(rule.value(), rule.fieldType()));
            case LTE -> col + " <= :" + sink.bind(resolver.resolve(rule.value(), rule.fieldType()));
            case LIKE -> col + " LIKE :" + sink.bind(resolver.resolve(rule.value(), FieldType.STRING));
            case NOT_LIKE -> col + " NOT LIKE :" + sink.bind(resolver.resolve(rule.value(), FieldType.STRING));
            case IN -> renderInList(col, true, resolver.resolveList(rule.value(), rule.fieldType()), sink);
            case NOT_IN -> renderInList(col, false, resolver.resolveList(rule.value(), rule.fieldType()), sink);
        };
    }

    private static String renderInList(String quotedCol, boolean positive,
                                        Collection<?> values, ParamSink sink) {
        if (values.isEmpty()) return positive ? "1=0" : "1=1";
        List<String> keys = sink.bindList(values);
        String inner = String.join(", ", keys.stream().map(k -> ":" + k).toList());
        return quotedCol + (positive ? " IN (" : " NOT IN (") + inner + ")";
    }

    private static String joinOr(List<String> clauses) {
        if (clauses.size() == 1) return clauses.get(0);
        return String.join(" OR ", clauses);
    }

    /** Allocates namespaced param keys ({@code rls_p0}, {@code rls_p1}, …) and
     *  collects the bound values for the final {@link SqlFilter}. */
    private static final class ParamSink {
        private final Map<String, Object> params = new LinkedHashMap<>();
        private int next = 0;

        String bind(Object value) {
            String key = "rls_p" + next++;
            params.put(key, pgBindable(value));
            return key;
        }

        List<String> bindList(Collection<?> values) {
            int slot = next++;
            List<String> keys = new ArrayList<>(values.size());
            int i = 0;
            for (Object v : values) {
                String key = "rls_p" + slot + "_" + i++;
                params.put(key, pgBindable(v));
                keys.add(key);
            }
            return keys;
        }

        /**
         * Postgres JDBC driver doesn't infer a SQL type for {@link Instant};
         * convert to {@link OffsetDateTime} (UTC) which maps cleanly to
         * {@code TIMESTAMPTZ}. Other JDBC drivers (MySQL, H2, …) accept
         * OffsetDateTime as well, so this is portable.
         */
        private static Object pgBindable(Object value) {
            if (value instanceof Instant i) return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
            return value;
        }

        Map<String, Object> snapshot() { return new HashMap<>(params); }
    }
}
