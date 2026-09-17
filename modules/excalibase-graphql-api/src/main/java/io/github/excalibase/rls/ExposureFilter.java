package io.github.excalibase.rls;

import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableExposure;
import io.github.excalibase.security.RlsOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns a project's {@link TableGrants} into the schema one caller is served.
 *
 * <p>This is the whole enforcement mechanism for exposure: a resource the caller
 * was not granted is <em>absent</em> from the {@link SchemaInfo} handed to the
 * compiler and to introspection, so GraphQL answers "unknown field" and REST
 * takes its existing unknown-table 404. Nothing downstream asks "may I?" — there
 * is nothing there to ask about.
 *
 * <p>Grant resources are resolved to the engine's canonical table keys here,
 * once, against the real schema. A bare name is accepted only when exactly one
 * schema has such a table; a name matching two schemas is dropped, because
 * "customer" must never quietly mean customer in <em>every</em> schema.
 *
 * <p>A table needs SELECT to appear at all. Write-only exposure is not
 * representable: every mutation this engine compiles returns the affected rows,
 * so a caller who may not read the table could read it through its own INSERT.
 */
public final class ExposureFilter {

    private static final Logger log = LoggerFactory.getLogger(ExposureFilter.class);

    /** Postgres spells a set-returning function's result "SETOF <table>". */
    private static final String SETOF_PREFIX = "SETOF ";

    private ExposureFilter() {
    }

    /** The caller's schema view plus the mutation operations they hold on it. */
    public record Result(SchemaInfo schemaInfo, TableExposure exposure) {}

    public static Result apply(SchemaInfo source, TableGrants grants, String callerRole) {
        if (grants == null || !grants.enforced()) {
            return new Result(source, TableExposure.UNRESTRICTED);
        }
        Map<String, Set<RlsOp>> byResource = resolveGrants(source, grants, callerRole);
        Set<String> readableTables = readableTables(source, byResource);
        Set<String> exposedProcedures = exposedProcedures(source, byResource, readableTables);

        Map<String, Set<RlsOp>> exposedTables = new HashMap<>();
        readableTables.forEach(table -> exposedTables.put(table, byResource.get(table)));

        return new Result(copyOf(source, readableTables, exposedProcedures),
                TableExposure.enforcing(exposedTables));
    }

    /**
     * Resolves every enabled grant that applies to {@code callerRole} to a canonical
     * schema key, unioning the operations of grants that land on the same resource.
     */
    private static Map<String, Set<RlsOp>> resolveGrants(SchemaInfo source, TableGrants grants, String callerRole) {
        Map<String, Set<RlsOp>> byResource = new HashMap<>();
        for (TableGrant grant : grants.enabledGrants()) {
            if (!grant.appliesToRole(callerRole)) {
                continue;
            }
            String canonicalKey = canonicalKey(source, grant.resource());
            if (canonicalKey == null) {
                continue;
            }
            byResource.computeIfAbsent(canonicalKey, key -> EnumSet.noneOf(RlsOp.class))
                    .addAll(operationsOf(grant));
        }
        return byResource;
    }

    private static Set<RlsOp> operationsOf(TableGrant grant) {
        Set<RlsOp> ops = EnumSet.noneOf(RlsOp.class);
        for (Operation operation : grant.operations()) {
            ops.add(RlsOp.valueOf(operation.name()));
        }
        return ops;
    }

    private static Set<String> readableTables(SchemaInfo source, Map<String, Set<RlsOp>> byResource) {
        Set<String> readable = new LinkedHashSet<>();
        for (String table : source.getTableNames()) {
            if (byResource.getOrDefault(table, Set.of()).contains(RlsOp.SELECT)) {
                readable.add(table);
            }
        }
        return readable;
    }

    /**
     * A function is exposed when it is granted SELECT in its own right <em>and</em>
     * the caller may read the table it returns rows of — Hasura's rule, because a
     * {@code SETOF orders} function is another way to read orders.
     *
     * <p>What a function's <em>body</em> writes to is out of reach from here: the
     * engine sees a name and a signature, not the statements inside. Constraining
     * that is the database's job, through the privileges the function runs with.
     */
    private static Set<String> exposedProcedures(SchemaInfo source, Map<String, Set<RlsOp>> byResource,
                                                 Set<String> readableTables) {
        Set<String> exposed = new LinkedHashSet<>();
        for (var entry : source.getStoredProcedures().entrySet()) {
            String procedureKey = entry.getKey();
            if (!byResource.getOrDefault(procedureKey, Set.of()).contains(RlsOp.SELECT)) {
                continue;
            }
            String returnTable = returnTableKey(source, procedureKey, entry.getValue().returnType());
            if (returnTable != null && !readableTables.contains(returnTable)) {
                log.info("exposure_function_hidden function={} return_table={} reason=no_select_on_return_table",
                        procedureKey, returnTable);
                continue;
            }
            exposed.add(procedureKey);
        }
        return exposed;
    }

    /**
     * The table a function returns rows of, or null when it returns something that
     * is not a table (a scalar, a composite, an inline TABLE(...) definition).
     */
    private static String returnTableKey(SchemaInfo source, String procedureKey, String returnType) {
        if (returnType == null || returnType.isBlank()) {
            return null;
        }
        String candidate = returnType.trim();
        if (candidate.regionMatches(true, 0, SETOF_PREFIX, 0, SETOF_PREFIX.length())) {
            candidate = candidate.substring(SETOF_PREFIX.length()).trim();
        }
        if (candidate.contains("(") || candidate.contains(" ")) {
            return null;
        }
        if (source.hasTable(candidate)) {
            return candidate;
        }
        int dot = procedureKey.indexOf('.');
        String procedureSchema = dot > 0 ? procedureKey.substring(0, dot) : null;
        if (procedureSchema != null && source.hasTable(procedureSchema + "." + candidate)) {
            return procedureSchema + "." + candidate;
        }
        return null;
    }

    /**
     * Maps a grant's resource onto a key the engine actually uses. Exact match wins;
     * a bare name is resolved only when it is unambiguous across schemas.
     */
    private static String canonicalKey(SchemaInfo source, String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        String trimmed = resource.trim();
        if (source.hasTable(trimmed) || source.getStoredProcedures().containsKey(trimmed)) {
            return trimmed;
        }
        if (trimmed.contains(".")) {
            log.warn("exposure_grant_unresolved resource={} reason=no_such_resource", trimmed);
            return null;
        }
        List<String> matches = new ArrayList<>();
        for (String key : allResourceKeys(source)) {
            int dot = key.lastIndexOf('.');
            if (dot >= 0 && key.substring(dot + 1).equals(trimmed)) {
                matches.add(key);
            }
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            log.warn("exposure_grant_unresolved resource={} reason=no_such_resource", trimmed);
        } else {
            log.warn("exposure_grant_rejected resource={} reason=ambiguous_across_schemas matches={}",
                    trimmed, matches);
        }
        return null;
    }

    private static Set<String> allResourceKeys(SchemaInfo source) {
        Set<String> all = new LinkedHashSet<>(source.getTableNames());
        all.addAll(source.getStoredProcedures().keySet());
        return all;
    }

    /**
     * Builds a fresh {@link SchemaInfo} holding only the kept tables and functions.
     * Relationships survive only when both ends do, so an embed can never reach a
     * table the caller was not granted.
     */
    private static SchemaInfo copyOf(SchemaInfo source, Set<String> tables, Set<String> procedures) {
        SchemaInfo filtered = new SchemaInfo();
        for (String table : tables) {
            copyTable(source, filtered, table);
        }
        copyForeignKeys(source, filtered, tables);
        for (String procedure : procedures) {
            filtered.addStoredProcedure(procedure, source.getStoredProcedures().get(procedure));
        }
        source.getEnumTypes().forEach((name, labels) -> labels.forEach(label -> filtered.addEnumValue(name, label)));
        source.getCompositeTypes().forEach((name, fields) ->
                fields.forEach(field -> filtered.addCompositeTypeField(name, field.name(), field.type())));
        source.getExtensions().forEach(filtered::addExtension);
        return filtered;
    }

    private static void copyTable(SchemaInfo source, SchemaInfo filtered, String table) {
        for (String column : source.getColumns(table)) {
            filtered.addColumn(table, column, source.getColumnType(table, column));
            String enumType = source.getEnumType(table, column);
            if (enumType != null) {
                filtered.addColumnEnumType(table, column, enumType);
            }
        }
        String schema = source.getTableSchema(table);
        if (schema != null) {
            filtered.setTableSchema(table, schema);
        }
        if (source.hasPrimaryKey(table)) {
            source.getPrimaryKeys(table).forEach(pk -> filtered.addPrimaryKey(table, pk));
        }
        if (source.isView(table)) {
            filtered.addView(table);
        }
        List<SchemaInfo.ComputedField> computed = source.getComputedFields(table);
        if (computed != null) {
            computed.forEach(field -> filtered.addComputedField(table, field.functionName(), field.returnType()));
        }
    }

    /**
     * Replays the forward FKs in a stable order so the reverse-relation names the
     * caller sees do not depend on hash iteration order.
     */
    private static void copyForeignKeys(SchemaInfo source, SchemaInfo filtered, Set<String> tables) {
        Map<String, SchemaInfo.FkInfo> ordered = new TreeMap<>(source.getAllForwardFks());
        for (var entry : ordered.entrySet()) {
            String fkKey = entry.getKey();
            SchemaInfo.FkInfo fk = entry.getValue();
            String fromTable = fkKey.substring(0, fkKey.lastIndexOf('.'));
            if (!tables.contains(fromTable) || !tables.contains(fk.refTable())) {
                continue;
            }
            if (fk.isComposite()) {
                filtered.addCompositeForeignKey(fromTable, fk.fkColumns(), fk.refTable(), fk.refColumns());
            } else {
                filtered.addForeignKey(fromTable, fk.fkColumn(), fk.refTable(), fk.refColumn());
            }
        }
    }
}
