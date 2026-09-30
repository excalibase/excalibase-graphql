package io.github.excalibase.schema;

import io.github.excalibase.security.RlsOp;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What one role may do with each table of the schema it is served, as far as the compiler and
 * introspection need to know: which operations exist, which columns a write may set, the role's row
 * cap and whether aggregates exist. Tables and columns the role may not read are already absent from
 * its {@link SchemaInfo}; this carries the rest. {@link #UNRESTRICTED} serves deployments without
 * permissions (authentication off, MySQL) and permits everything the schema holds.
 */
public final class TableAccess {

    public static final TableAccess UNRESTRICTED = new TableAccess(false, Map.of());

    /**
     * @param insertColumns columns an insert may set, presets excluded
     * @param updateColumns columns an update may set, presets excluded
     * @param rowLimit      the role's cap on rows one select returns, or null for none of its own
     */
    public record Rights(Set<RlsOp> operations, Set<String> insertColumns, Set<String> updateColumns,
                         Integer rowLimit, boolean aggregations) {
        public Rights {
            operations = operations.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(operations));
            insertColumns = Collections.unmodifiableSet(new LinkedHashSet<>(insertColumns));
            updateColumns = Collections.unmodifiableSet(new LinkedHashSet<>(updateColumns));
        }
    }

    private final boolean enforced;
    private final Map<String, Rights> tables;

    private TableAccess(boolean enforced, Map<String, Rights> tables) {
        this.enforced = enforced;
        this.tables = Map.copyOf(tables);
    }

    public static TableAccess enforcing(Map<String, Rights> tables) {
        return new TableAccess(true, Objects.requireNonNull(tables, "tables"));
    }

    public boolean enforced() {
        return enforced;
    }

    public boolean permits(String table, RlsOp operation) {
        return !enforced || rights(table).map(held -> held.operations().contains(operation)).orElse(false);
    }

    public boolean allowsAggregations(String table) {
        return !enforced || rights(table).map(Rights::aggregations).orElse(false);
    }

    /** The role's own row cap on {@code table}, or null when it has none. */
    public Integer rowLimit(String table) {
        return enforced ? rights(table).map(Rights::rowLimit).orElse(null) : null;
    }

    /** Columns a client may set on {@code table} for an INSERT or an UPDATE. */
    public Set<String> settableColumns(String table, RlsOp operation, SchemaInfo view) {
        if (!enforced) {
            return view.getColumns(table);
        }
        return rights(table).map(held -> switch (operation) {
            case INSERT -> held.insertColumns();
            case UPDATE -> held.updateColumns();
            default -> Set.<String>of();
        }).orElse(Set.of());
    }

    private Optional<Rights> rights(String table) {
        return Optional.ofNullable(tables.get(table));
    }
}
