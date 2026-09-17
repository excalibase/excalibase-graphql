package io.github.excalibase.schema;

import io.github.excalibase.security.RlsOp;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which operations one caller may reach on each table of the schema they were
 * served. Built once, when that caller's {@link SchemaInfo} view is built, and
 * carried alongside it — the compiler never recomputes it per request.
 *
 * <p>Table-level exposure needs no representation here: a table the caller may
 * not read is simply absent from their {@code SchemaInfo}, so it has no type and
 * no query field. This type exists only because one table can be readable but
 * not writable, which is a difference in the set of mutation fields rather than
 * in the set of tables.
 *
 * <p>{@link #UNRESTRICTED} is the shape for every project that has not opted in,
 * and for the single-tenant/no-project path, so the feature is inert by default.
 */
public record TableExposure(boolean enforced, Map<String, Set<RlsOp>> operationsByTable) {

    public static final TableExposure UNRESTRICTED = new TableExposure(false, Map.of());

    public TableExposure {
        Map<String, Set<RlsOp>> copy = new HashMap<>();
        if (operationsByTable != null) {
            operationsByTable.forEach((table, ops) -> copy.put(table, Set.copyOf(ops)));
        }
        operationsByTable = Map.copyOf(copy);
    }

    public static TableExposure enforcing(Map<String, Set<RlsOp>> operationsByTable) {
        return new TableExposure(true, operationsByTable);
    }

    /** True when the caller may run {@code operation} against {@code tableKey}. */
    public boolean permits(String tableKey, RlsOp operation) {
        if (!enforced) {
            return true;
        }
        return operationsByTable.getOrDefault(tableKey, Set.of()).contains(operation);
    }

    /** True when {@code tableKey} is part of the caller's schema at all. */
    public boolean exposes(String tableKey) {
        return !enforced || !operationsByTable.getOrDefault(tableKey, Set.of()).isEmpty();
    }
}
