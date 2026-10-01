package io.github.excalibase.access;

import io.github.excalibase.schema.SchemaInfo;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the schema one role is served: its selectable tables with only their selectable columns,
 * and relationships only where both tables and every join column are selectable. Columns the role
 * may write but not read are typed without being listed; a table it may insert into but not select
 * is a write-only table, reachable by nothing that reads. A nested insert goes through a relationship
 * when the role may insert into both of its tables, whatever it may select, as in Hasura; such
 * relationships keep the names the unfiltered schema gives them. Functions are not part of the view: the ones
 * a role may call are the plan's tracked functions. Computed fields are left out for every role.
 */
final class RoleView {

    private RoleView() {
    }

    static SchemaInfo build(SchemaInfo reflected, Map<String, TableRules> rules) {
        SchemaInfo view = new SchemaInfo();
        rules.values().forEach(table -> copyTable(reflected, view, table));
        copyForeignKeys(reflected, view, rules);
        copyInsertRelationships(reflected, view, rules);
        reflected.getEnumTypes().forEach((name, labels) -> labels.forEach(label -> view.addEnumValue(name, label)));
        reflected.getCompositeTypes().forEach((name, fields) ->
                fields.forEach(field -> view.addCompositeTypeField(name, field.name(), field.type())));
        reflected.getExtensions().forEach(view::addExtension);
        return view;
    }

    private static void copyTable(SchemaInfo reflected, SchemaInfo view, TableRules rules) {
        String table = rules.table();
        Set<String> readable = rules.select().map(TableRules.SelectRule::columns).orElse(Set.of());
        readable.forEach(column -> copyColumn(reflected, view, table, column, false));
        writeOnly(rules, readable).forEach(column -> copyColumn(reflected, view, table, column, true));
        String schema = reflected.getTableSchema(table);
        if (schema != null) {
            view.setTableSchema(table, schema);
        }
        if (reflected.isView(table)) {
            view.addView(table);
        }
        if (rules.insertOnly()) {
            view.addWriteOnlyTable(table);
        }
        if (reflected.hasPrimaryKey(table) && readable.containsAll(reflected.getPrimaryKeys(table))) {
            reflected.getPrimaryKeys(table).forEach(key -> view.addPrimaryKey(table, key));
        }
    }

    private static Set<String> writeOnly(TableRules rules, Set<String> readable) {
        Set<String> written = new HashSet<>();
        rules.insert().ifPresent(insert -> {
            written.addAll(insert.columns());
            written.addAll(insert.presets().keySet());
        });
        rules.update().ifPresent(update -> {
            written.addAll(update.columns());
            written.addAll(update.presets().keySet());
        });
        written.removeAll(readable);
        return written;
    }

    private static void copyColumn(SchemaInfo reflected, SchemaInfo view, String table, String column,
                                   boolean writeOnly) {
        String type = reflected.getColumnType(table, column);
        if (writeOnly) {
            view.addWriteOnlyColumn(table, column, type);
        } else {
            view.addColumn(table, column, type);
        }
        String userType = reflected.getEnumType(table, column);
        if (userType != null) {
            view.addColumnEnumType(table, column, userType);
        }
    }

    /** Replayed in a stable order so reverse-relationship names do not depend on hash order. */
    private static void copyForeignKeys(SchemaInfo reflected, SchemaInfo view, Map<String, TableRules> rules) {
        new TreeMap<>(reflected.getAllForwardFks()).forEach((key, fk) -> {
            String fromTable = owner(key);
            if (joinable(rules, fromTable, fk)) {
                addForeignKey(view, fromTable, fk);
            }
        });
    }

    private static void copyInsertRelationships(SchemaInfo reflected, SchemaInfo view, Map<String, TableRules> rules) {
        Map<String, SchemaInfo.ReverseFkInfo> arrays = new TreeMap<>();
        reflected.getAllReverseFks().forEach((key, relation) -> {
            if (insertable(rules, owner(key)) && insertable(rules, relation.childTable())) {
                arrays.put(key, relation);
            }
        });
        Map<String, SchemaInfo.FkInfo> objects = new TreeMap<>();
        reflected.getAllForwardFks().forEach((key, relation) -> {
            if (insertable(rules, owner(key)) && insertable(rules, relation.refTable())) {
                objects.put(key, relation);
            }
        });
        view.setInsertRelationships(arrays, objects);
    }

    private static boolean insertable(Map<String, TableRules> rules, String table) {
        TableRules held = rules.get(table);
        return held != null && held.insert().isPresent();
    }

    private static void addForeignKey(SchemaInfo view, String fromTable, SchemaInfo.FkInfo fk) {
        if (fk.isComposite()) {
            view.addCompositeForeignKey(fromTable, fk.fkColumns(), fk.refTable(), fk.refColumns());
        } else {
            view.addForeignKey(fromTable, fk.fkColumn(), fk.refTable(), fk.refColumn());
        }
    }

    private static boolean joinable(Map<String, TableRules> rules, String fromTable, SchemaInfo.FkInfo fk) {
        return selectable(rules, fromTable, fk.fkColumns()) && selectable(rules, fk.refTable(), fk.refColumns());
    }

    private static boolean selectable(Map<String, TableRules> rules, String table, Collection<String> columns) {
        TableRules held = rules.get(table);
        return held != null && held.select().map(select -> select.columns().containsAll(columns)).orElse(false);
    }

    /** A forward-FK key is {@code <table>.<field>}; the table is everything before the last dot. */
    private static String owner(String fkKey) {
        return fkKey.substring(0, fkKey.lastIndexOf('.'));
    }
}
