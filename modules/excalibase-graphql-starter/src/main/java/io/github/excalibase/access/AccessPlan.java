package io.github.excalibase.access;

import io.github.excalibase.permissions.RolePermissions;
import io.github.excalibase.permissions.TrackedFunction;
import io.github.excalibase.permissions.compile.SessionBinding;
import io.github.excalibase.schema.ExposedFunction;
import io.github.excalibase.schema.ExposedFunctions;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsOp;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final ExposedFunctions functions;

    private AccessPlan(SchemaInfo reflected, SchemaInfo view, TableAccess access, Map<String, TableRules> rules,
                       boolean allAccess, ExposedFunctions functions) {
        this.reflected = reflected;
        this.view = view;
        this.access = access;
        this.rules = Map.copyOf(rules);
        this.allAccess = allAccess;
        this.functions = functions;
    }

    /** Every table, and no function: nothing is tracked. */
    public static AccessPlan allAccess(SchemaInfo reflected) {
        return allAccess(reflected, List.of());
    }

    /** Every table and every valid tracked function; an untracked function is not reachable even so. */
    public static AccessPlan allAccess(SchemaInfo reflected, List<TrackedFunction> tracked) {
        Objects.requireNonNull(reflected, "reflected");
        return new AccessPlan(reflected, reflected, TableAccess.UNRESTRICTED, Map.of(), true,
                FunctionResolver.resolve(reflected, tracked, function -> true));
    }

    /**
     * A tracked function is callable when the role may select its return table and holds an explicit
     * function permission, or, for a query function tracked with inference on, by that select alone.
     */
    public static AccessPlan forRole(SchemaInfo reflected, RolePermissions permissions) {
        Objects.requireNonNull(reflected, "reflected");
        Map<String, TableRules> rules = RulesResolver.resolve(reflected, permissions);
        ExposedFunctions functions = FunctionResolver.resolve(reflected, permissions.trackedFunctions(),
                function -> selectable(rules, function.returnTable())
                        && (permissions.explicitlyPermittedFunctions().contains(function.function())
                            || inferred(permissions, function)));
        return new AccessPlan(reflected, RoleView.build(reflected, rules), rightsOf(rules), rules, false, functions);
    }

    private static boolean selectable(Map<String, TableRules> rules, String table) {
        TableRules held = rules.get(table);
        return held != null && held.select().isPresent();
    }

    private static boolean inferred(RolePermissions permissions, ExposedFunction function) {
        return function.operation() == ExposedFunction.Operation.QUERY
                && permissions.trackedFunction(function.function()).map(TrackedFunction::inferPermissions).orElse(false);
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

    /** The tracked functions the role may call; their rows obey the return table's select permission. */
    public ExposedFunctions functions() {
        return functions;
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
        return rules(table).flatMap(TableRules::select)
                .map(select -> new SelectChangeFilter(table, select, reflected, binding, IncompleteImageWarning.SHARED));
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
                held.select().map(TableRules.SelectRule::limit).orElse(null),
                held.select().map(TableRules.SelectRule::aggregations).orElse(false))));
        return TableAccess.enforcing(rights);
    }

    private static Set<RlsOp> operations(TableRules held) {
        Set<RlsOp> operations = EnumSet.noneOf(RlsOp.class);
        held.select().ifPresent(ignored -> operations.add(RlsOp.SELECT));
        held.insert().ifPresent(ignored -> operations.add(RlsOp.INSERT));
        held.update().ifPresent(ignored -> operations.add(RlsOp.UPDATE));
        held.delete().ifPresent(ignored -> operations.add(RlsOp.DELETE));
        return operations;
    }
}
