package io.github.excalibase.access;

import io.github.excalibase.permissions.RolePermissions;
import io.github.excalibase.permissions.compile.SessionBinding;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsOp;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Everything one role may reach in one project (docs/features/permissions.md §7), built once from the
 * project's reflected schema and the role's permissions and then only read. The role's schema, the
 * request guards and the realtime row matcher all come from here and nowhere else.
 *
 * <p>An all-access plan (the {@code service} role, or a deployment without permissions) serves the
 * whole reflected schema with no guards.
 */
public final class AccessPlan {

    private final SchemaInfo reflected;
    private final SchemaInfo view;
    private final TableAccess access;
    private final Map<String, TableRules> rules;
    private final boolean allAccess;

    private AccessPlan(SchemaInfo reflected, SchemaInfo view, TableAccess access, Map<String, TableRules> rules,
                       boolean allAccess) {
        this.reflected = reflected;
        this.view = view;
        this.access = access;
        this.rules = Map.copyOf(rules);
        this.allAccess = allAccess;
    }

    public static AccessPlan allAccess(SchemaInfo reflected) {
        Objects.requireNonNull(reflected, "reflected");
        return new AccessPlan(reflected, reflected, TableAccess.UNRESTRICTED, Map.of(), true);
    }

    public static AccessPlan forRole(SchemaInfo reflected, RolePermissions permissions) {
        Objects.requireNonNull(reflected, "reflected");
        Map<String, TableRules> rules = RulesResolver.resolve(reflected, permissions);
        return new AccessPlan(reflected, RoleView.build(reflected, rules), rightsOf(rules), rules, false);
    }

    /** The schema the role is served; the compiler and introspection see nothing else. */
    public SchemaInfo view() {
        return view;
    }

    public TableAccess access() {
        return access;
    }

    public boolean allAccess() {
        return allAccess;
    }

    /** The guard one request applies: this plan's rules bound to the request's session variables. */
    public RequestGuard guard(Map<String, String> sessionVariables) {
        return new RequestGuard(this, sessionVariables);
    }

    /**
     * The realtime filter for one subscription on {@code table}, or empty when the role cannot select
     * it. Variables are bound now, so a missing one fails the subscription rather than each change.
     */
    public Optional<ChangeFilter> changes(String table, Map<String, String> sessionVariables) {
        if (allAccess) {
            return reflected.hasTable(table) ? Optional.of(ChangeFilter.PASS_THROUGH) : Optional.empty();
        }
        SessionBinding binding = new SessionBinding(sessionVariables);
        return rules(table).map(held -> new SelectChangeFilter(table, held.select(), reflected, binding));
    }

    SchemaInfo reflected() {
        return reflected;
    }

    Optional<TableRules> rules(String table) {
        return Optional.ofNullable(rules.get(table));
    }

    private static TableAccess rightsOf(Map<String, TableRules> rules) {
        Map<String, TableAccess.Rights> rights = new LinkedHashMap<>();
        rules.forEach((table, held) -> rights.put(table, new TableAccess.Rights(operations(held),
                held.insert().map(TableRules.InsertRule::columns).orElse(Set.of()),
                held.update().map(TableRules.UpdateRule::columns).orElse(Set.of()),
                held.select().limit(), held.select().aggregations())));
        return TableAccess.enforcing(rights);
    }

    private static Set<RlsOp> operations(TableRules held) {
        Set<RlsOp> operations = EnumSet.of(RlsOp.SELECT);
        held.insert().ifPresent(ignored -> operations.add(RlsOp.INSERT));
        held.update().ifPresent(ignored -> operations.add(RlsOp.UPDATE));
        held.delete().ifPresent(ignored -> operations.add(RlsOp.DELETE));
        return operations;
    }
}
