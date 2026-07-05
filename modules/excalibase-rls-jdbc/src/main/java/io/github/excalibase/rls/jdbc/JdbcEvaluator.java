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
import io.github.excalibase.rls.ResourceMatcher;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.RelationPredicate;
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
    private final QuoteStyle quoteStyle;

    public JdbcEvaluator(List<Policy> policies) {
        this(policies, List.of());
    }

    public JdbcEvaluator(List<Policy> policies, List<ColumnPolicy> columnPolicies) {
        this(policies, columnPolicies, QuoteStyle.ANSI);
    }

    /**
     * @param quoteStyle how column identifiers are quoted in emitted SQL —
     *                   {@link QuoteStyle#ANSI} for Postgres (default),
     *                   {@link QuoteStyle#BACKTICK} for MySQL.
     */
    public JdbcEvaluator(List<Policy> policies, List<ColumnPolicy> columnPolicies, QuoteStyle quoteStyle) {
        this.policies = (policies == null) ? List.of() : List.copyOf(policies);
        this.columnMasker = new ColumnMasker(columnPolicies);
        this.quoteStyle = (quoteStyle == null) ? QuoteStyle.ANSI : quoteStyle;
    }

    private String quote(String validatedIdentifier) {
        return quoteStyle.quote(validatedIdentifier);
    }

    public SqlFilter compile(String resource, UserContext ctx, Operation op) {
        return compile(resource, ctx, op, null);
    }

    /**
     * @param outerAlias the alias (or name) the calling query gives the outer
     *                   table — used to correlate relationship/EXISTS subqueries
     *                   back to it. The compiler aliases tables, so the table
     *                   name alone won't resolve; pass that alias here. Null
     *                   falls back to the unqualified resource name (correct for
     *                   un-aliased callers like the differential tests).
     */
    public SqlFilter compile(String resource, UserContext ctx, Operation op, String outerAlias) {
        VariableResolver resolver = new VariableResolver(ctx);
        ParamSink sink = new ParamSink();
        SqlFilter base = compileForOp(resource, ctx, op, resolver, sink, outerAlias);

        // A row must be SELECT-visible to be modified: the read filter is AND'd
        // into UPDATE/DELETE so a caller can never modify a row it cannot see.
        // Enforced in the emitted SQL, so it holds on any backing database.
        if (op == Operation.UPDATE || op == Operation.DELETE) {
            SqlFilter read = compileForOp(resource, ctx, Operation.SELECT, resolver, sink, outerAlias);
            return andVisibility(base, read, sink);
        }
        return base;
    }

    private SqlFilter compileForOp(String resource, UserContext ctx, Operation op,
                                   VariableResolver resolver, ParamSink sink, String outerAlias) {
        List<Policy> inScope = inScope(resource, ctx, op);
        // Reference used to correlate EXISTS subqueries back to the outer table.
        // The compiler aliases tables (already-quoted alias), so prefer that;
        // else quote the bare table name for un-aliased callers.
        String outerRef = (outerAlias != null && !outerAlias.isBlank())
            ? outerAlias
            : quote(SqlIdentifier.checkColumn(unqualified(resource)));

        // Scalar rules are normally emitted bare ("owner_id = :p") because they sit
        // inside a `FROM table alias WHERE ...` where the column is unambiguous. When
        // the caller supplies an explicit alias, qualify with it so the predicate is
        // safe in contexts where a bare column would be ambiguous — notably
        // ON CONFLICT ... DO UPDATE WHERE, where EXCLUDED shares the column name.
        String scalarPrefix = (outerAlias != null && !outerAlias.isBlank()) ? outerAlias + "." : "";

        List<String> allowSqls = new ArrayList<>();
        List<String> denySqls = new ArrayList<>();

        for (Policy p : inScope) {
            String pSql = renderPolicy(p, outerRef, scalarPrefix, resolver, sink);
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

    /** AND the read-visibility filter into a write (UPDATE/DELETE) filter. */
    private static SqlFilter andVisibility(SqlFilter write, SqlFilter read, ParamSink sink) {
        if (write.equals(SqlFilter.DENY_ALL) || read.equals(SqlFilter.DENY_ALL)) return SqlFilter.DENY_ALL;
        if (write.equals(SqlFilter.UNRESTRICTED)) return read;
        if (read.equals(SqlFilter.UNRESTRICTED)) return write;
        return new SqlFilter("(" + write.sql() + ") AND (" + read.sql() + ")", sink.snapshot());
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
                selectList.add(quote(safe));
            } else {
                selectList.add(renderMaskedColumn(safe, mode));
            }
        }
        return new SqlProjection(selectList, Map.of(), Set.copyOf(plan.hidden()));
    }

    private String renderMaskedColumn(String column, MaskMode mode) {
        return switch (mode) {
            case NULL -> "NULL AS " + quote(column);
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
                && ResourceMatcher.matches(p.resource(), resource)
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

    private String renderPolicy(Policy policy, String outerTable, String scalarPrefix,
                                VariableResolver resolver, ParamSink sink) {
        List<String> parts = new ArrayList<>(policy.rules().size() + policy.relations().size());
        for (Rule r : policy.rules()) {
            parts.add(renderRule(scalarPrefix, r, resolver, sink));
        }
        for (RelationPredicate rel : policy.relations()) {
            parts.add(renderRelation(rel, outerTable, resolver, sink));
        }
        if (parts.isEmpty()) return null;
        if (parts.size() == 1) return parts.get(0);
        String joiner = policy.ruleLogic() == LogicOperator.AND ? " AND " : " OR ";
        return "(" + String.join(joiner, parts) + ")";
    }

    /**
     * {@code EXISTS (SELECT 1 FROM related rel WHERE rel.fk = outer.pk [AND/OR subRules])}
     * — portable across Postgres and MySQL via the active quote style. Sub-rules
     * are scalar rules over the related table, qualified with the subquery alias.
     */
    private String renderRelation(RelationPredicate rel, String outerRef,
                                  VariableResolver resolver, ParamSink sink) {
        String alias = sink.nextAlias();
        String related = quoteTable(rel.relatedResource());
        String fk = alias + "." + quote(SqlIdentifier.checkColumn(rel.foreignKey()));
        // outerRef is a ready table reference (an already-quoted alias, or a
        // quoted bare table name) — use it directly, do not re-quote.
        String pk = outerRef + "." + quote(SqlIdentifier.checkColumn(rel.parentKey()));

        StringBuilder sql = new StringBuilder("EXISTS (SELECT 1 FROM ")
            .append(related).append(" ").append(alias)
            .append(" WHERE ").append(fk).append(" = ").append(pk);
        if (!rel.subRules().isEmpty()) {
            List<String> subs = new ArrayList<>(rel.subRules().size());
            for (Rule r : rel.subRules()) {
                subs.add(renderRule(alias + ".", r, resolver, sink));
            }
            String joiner = rel.subLogic() == LogicOperator.AND ? " AND " : " OR ";
            sql.append(" AND (").append(String.join(joiner, subs)).append(")");
        }
        return sql.append(")").toString();
    }

    /** Quotes a table name with optional schema qualifier using the active quote style. */
    private String quoteTable(String name) {
        int dot = name.indexOf('.');
        if (dot < 0) return quote(SqlIdentifier.checkColumn(name));
        return quote(SqlIdentifier.checkColumn(name.substring(0, dot)))
            + "." + quote(SqlIdentifier.checkColumn(name.substring(dot + 1)));
    }

    private String renderRule(String qualifier, Rule rule, VariableResolver resolver, ParamSink sink) {
        String col = colExpr(qualifier, rule);

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

    /**
     * Column expression for a rule's field. A plain column is quoted as-is; a
     * dotted field is a JSON path ({@code meta.region}), cast to the rule's
     * declared field type — the SQL equivalent of the in-memory matcher's
     * nested-map navigation. Postgres ({@code "meta"->>'region'}) and MySQL
     * ({@code `meta`->>'$.region'}) JSON syntaxes differ, selected by quote style.
     */
    private String colExpr(String qualifier, Rule rule) {
        String field = rule.field();
        if (field.indexOf('.') < 0) {
            return qualifier + quote(SqlIdentifier.checkColumn(field));
        }
        String[] parts = field.split("\\.");
        String base = qualifier + quote(SqlIdentifier.checkColumn(parts[0]));

        if (quoteStyle == QuoteStyle.BACKTICK) {
            StringBuilder path = new StringBuilder("$");
            for (int i = 1; i < parts.length; i++) {
                path.append('.').append(SqlIdentifier.checkColumn(parts[i]));
            }
            String expr = base + "->>'" + path + "'";
            String cast = mysqlJsonCast(rule.fieldType());
            return cast == null ? expr : "CAST(" + expr + " AS " + cast + ")";
        }

        StringBuilder expr = new StringBuilder(base);
        for (int i = 1; i < parts.length; i++) {
            String key = SqlIdentifier.checkColumn(parts[i]);
            expr.append(i == parts.length - 1 ? "->>'" : "->'").append(key).append("'");
        }
        String cast = pgJsonCast(rule.fieldType());
        return cast == null ? expr.toString() : "(" + expr + ")::" + cast;
    }

    private static String pgJsonCast(FieldType type) {
        return switch (type) {
            case STRING -> null;
            case UUID -> "uuid";
            case INTEGER -> "integer";
            case LONG -> "bigint";
            case BOOLEAN -> "boolean";
            case DOUBLE -> "double precision";
            case DECIMAL -> "numeric";
            case DATE -> "date";
            case DATETIME -> "timestamptz";
        };
    }

    private static String mysqlJsonCast(FieldType type) {
        return switch (type) {
            case INTEGER, LONG -> "SIGNED";
            case DOUBLE, DECIMAL -> "DECIMAL(38,10)";
            case DATE -> "DATE";
            case DATETIME -> "DATETIME";
            case STRING, UUID, BOOLEAN -> null;
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

    /** Table name without its schema qualifier, for correlating a subquery back
     *  to the outer table ({@code public.orders} → {@code orders}). */
    private static String unqualified(String resource) {
        if (resource == null) return null;
        int dot = resource.lastIndexOf('.');
        return dot < 0 ? resource : resource.substring(dot + 1);
    }

    /** Allocates namespaced param keys ({@code rls_p0}, {@code rls_p1}, …) and
     *  collects the bound values for the final {@link SqlFilter}. */
    private static final class ParamSink {
        private final Map<String, Object> params = new LinkedHashMap<>();
        private int next = 0;
        private int aliasSeq = 0;

        /** Unique subquery alias per relationship predicate (self-join safe). */
        String nextAlias() {
            return "rls_rel" + aliasSeq++;
        }

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
