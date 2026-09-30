package io.github.excalibase.access;

import io.github.excalibase.access.TableRules.DeleteRule;
import io.github.excalibase.access.TableRules.InsertRule;
import io.github.excalibase.access.TableRules.SelectRule;
import io.github.excalibase.access.TableRules.UpdateRule;
import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.ColumnSet;
import io.github.excalibase.permissions.DeletePermission;
import io.github.excalibase.permissions.InsertPermission;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.PresetValue;
import io.github.excalibase.permissions.RolePermissions;
import io.github.excalibase.permissions.SelectPermission;
import io.github.excalibase.permissions.TablePermissions;
import io.github.excalibase.permissions.UpdatePermission;
import io.github.excalibase.permissions.compile.ExpressionCheck;
import io.github.excalibase.permissions.compile.PresetValues;
import io.github.excalibase.schema.SchemaInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves a role's permissions against the reflected schema. A permission that names a table,
 * column or relationship the database does not have, or a literal its column cannot hold, is dropped
 * and logged as {@code permission_invalid}: dropping it denies, which never widens what the role holds.
 */
final class RulesResolver {

    private static final Logger log = LoggerFactory.getLogger(RulesResolver.class);

    private final SchemaInfo reflected;
    private final String role;

    private RulesResolver(SchemaInfo reflected, String role) {
        this.reflected = reflected;
        this.role = role;
    }

    static Map<String, TableRules> resolve(SchemaInfo reflected, RolePermissions permissions) {
        RulesResolver resolver = new RulesResolver(reflected, permissions.role());
        Map<String, TableRules> rules = new LinkedHashMap<>();
        permissions.tables().values().forEach(entry ->
                resolver.table(entry).ifPresent(resolved -> rules.put(resolved.table(), resolved)));
        return rules;
    }

    private Optional<TableRules> table(TablePermissions entry) {
        String table = entry.table();
        if (!reflected.hasTable(table)) {
            invalid(table, "table", "no such table");
            return Optional.empty();
        }
        Optional<SelectRule> select = entry.select().flatMap(permission -> valid(table, "select",
                () -> select(table, permission)));
        if (select.isEmpty()) {
            return Optional.empty();
        }
        boolean writable = !reflected.isView(table);
        return Optional.of(new TableRules(table, select.get(),
                entry.insert().filter(ignored -> writable)
                        .flatMap(permission -> valid(table, "insert", () -> insert(table, permission))),
                entry.update().filter(ignored -> writable)
                        .flatMap(permission -> valid(table, "update", () -> update(table, permission))),
                entry.delete().filter(ignored -> writable)
                        .flatMap(permission -> valid(table, "delete", () -> delete(table, permission)))));
    }

    private SelectRule select(String table, SelectPermission permission) {
        ExpressionCheck.check(permission.filter(), table, reflected);
        return new SelectRule(permission.filter(), columns(table, permission.columns()), permission.limit(),
                permission.allowAggregations());
    }

    private InsertRule insert(String table, InsertPermission permission) {
        ExpressionCheck.check(permission.check(), table, reflected);
        Map<String, PresetValue> presets = presets(table, permission.set());
        return new InsertRule(permission.check(), settable(table, permission.columns(), presets), presets);
    }

    private UpdateRule update(String table, UpdatePermission permission) {
        ExpressionCheck.check(permission.filter(), table, reflected);
        ExpressionCheck.check(permission.check(), table, reflected);
        Map<String, PresetValue> presets = presets(table, permission.set());
        return new UpdateRule(permission.filter(), permission.check(),
                settable(table, permission.columns(), presets), presets);
    }

    private DeleteRule delete(String table, DeletePermission permission) {
        ExpressionCheck.check(permission.filter(), table, reflected);
        return new DeleteRule(permission.filter());
    }

    private Set<String> columns(String table, ColumnSet columnSet) {
        if (columnSet.all()) {
            return new LinkedHashSet<>(reflected.getColumns(table));
        }
        for (String column : columnSet.names()) {
            if (!reflected.getColumns(table).contains(column)) {
                throw new PermissionEvaluationException(PermissionEvaluationException.UNKNOWN_COLUMN,
                        "Unknown column " + column + " on " + table);
            }
        }
        return new LinkedHashSet<>(columnSet.names());
    }

    /** A preset column is never settable by the client, even when the permission also lists it. */
    private Set<String> settable(String table, ColumnSet columnSet, Map<String, PresetValue> presets) {
        Set<String> settable = columns(table, columnSet);
        settable.removeAll(presets.keySet());
        return settable;
    }

    private Map<String, PresetValue> presets(String table, Map<String, PresetValue> set) {
        set.forEach((column, value) -> PresetValues.check(value, table, column, reflected));
        return set;
    }

    private <T> Optional<T> valid(String table, String operation, Supplier<T> resolver) {
        try {
            return Optional.of(resolver.get());
        } catch (PermissionEvaluationException e) {
            invalid(table, operation, e.getMessage());
            return Optional.empty();
        }
    }

    private void invalid(String table, String operation, String reason) {
        log.warn("permission_invalid role={} table={} operation={} reason={}", role, table, operation, reason);
    }

    static boolean isTrue(BoolExp expression) {
        return expression instanceof BoolExp.True;
    }
}
