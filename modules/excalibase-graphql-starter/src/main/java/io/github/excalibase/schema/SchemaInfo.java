package io.github.excalibase.schema;

import io.github.excalibase.spi.SchemaLoader;
import io.github.excalibase.spi.SqlEngineFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Loads table/column/FK metadata from PostgreSQL information_schema.
 * Lightweight — no ORM, no reflection framework.
 */
public class SchemaInfo {

    // table → schema name (e.g. "public", "dvdrental")
    private final Map<String, String> tableSchema = new HashMap<>();
    // table → Set<column_name>
    private final Map<String, Set<String>> tableColumns = new HashMap<>();
    // table → column → type
    private final Map<String, Map<String, String>> columnTypes = new HashMap<>();
    // table → column → type, for columns a role may write but not read: typed, never listed
    private final Map<String, Map<String, String>> writeOnlyColumnTypes = new HashMap<>();
    // tables a role may insert into but not read: no columns, no read path, only their write-only columns
    private final Set<String> writeOnlyTables = new LinkedHashSet<>();
    // table → primary key columns (ordered)
    private final Map<String, List<String>> primaryKeys = new HashMap<>();
    // table.fieldName → FkInfo (forward FK: fieldName = schemaFieldName(schema, fkColumn))
    private final Map<String, FkInfo> forwardFks = new HashMap<>();
    // table.fieldName → ReverseFkInfo (reverse FK: fieldName = schemaFieldName(schema, childTable))
    private final Map<String, ReverseFkInfo> reverseFks = new HashMap<>();
    // enum name → list of enum values
    private final Map<String, List<String>> enumTypes = new HashMap<>();
    // "table.column" → enum type name (e.g. "priority_level")
    private final Map<String, String> columnEnumType = new HashMap<>();
    // table → list of computed fields
    private final Map<String, List<ComputedField>> computedFields = new HashMap<>();
    // set of view names (read-only — no mutations)
    private final Set<String> viewNames = new HashSet<>();
    // functions and procedures as reflected: name → FunctionInfo
    private final Map<String, FunctionInfo> functions = new LinkedHashMap<>();
    // composite types: typeName → list of fields
    private final Map<String, List<CompositeTypeField>> compositeTypes = new LinkedHashMap<>();
    // installed Postgres extensions: extname → extversion. Populated by the
    // bulk introspection query so query-builders can light up FTS / vector /
    // other extension-gated features without an extra round-trip.
    private final Map<String, String> extensions = new LinkedHashMap<>();

    public void load(JdbcTemplate jdbc, String schema) {
        load(jdbc, schema, "postgres");
    }

    public void load(JdbcTemplate jdbc, String schema, String databaseType) {
        SchemaLoader loader = SqlEngineFactory.create(databaseType).schemaLoader();
        load(jdbc, schema, loader);
    }

    public void load(JdbcTemplate jdbc, String schema, SchemaLoader loader) {
        loader.loadColumns(jdbc, schema, this);
        loader.loadPrimaryKeys(jdbc, schema, this);
        loader.loadForeignKeys(jdbc, schema, this);
        loader.loadEnums(jdbc, schema, this);
        loader.loadCompositeTypes(jdbc, schema, this);
        loader.loadComputedFields(jdbc, schema, this);
        loader.loadViews(jdbc, schema, this);
        loader.loadFunctions(jdbc, schema, this);
    }

    public void removeTable(String table) {
        tableSchema.remove(table);
        Set<String> cols = tableColumns.remove(table);
        columnTypes.remove(table);
        writeOnlyColumnTypes.remove(table);
        writeOnlyTables.remove(table);
        primaryKeys.remove(table);
        viewNames.remove(table);
        computedFields.remove(table);
        // Clean column enum type entries
        if (cols != null) {
            for (String col : cols) {
                columnEnumType.remove(table + "." + col);
            }
        }
    }

    public void clearAll() {
        tableSchema.clear();
        tableColumns.clear();
        columnTypes.clear();
        writeOnlyColumnTypes.clear();
        writeOnlyTables.clear();
        primaryKeys.clear();
        forwardFks.clear();
        reverseFks.clear();
        enumTypes.clear();
        columnEnumType.clear();
        computedFields.clear();
        viewNames.clear();
        functions.clear();
        compositeTypes.clear();
    }

    // === Public mutators for SchemaLoader implementations ===

    public void addPrimaryKey(String table, String column) {
        primaryKeys.computeIfAbsent(table, k -> new ArrayList<>()).add(column);
    }

    public void addColumn(String table, String column, String type) {
        tableColumns.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(column);
        columnTypes.computeIfAbsent(table, k -> new HashMap<>()).put(column, type);
    }

    /** A column a role may write but not read: its type is known, but it is not one of the table's columns. */
    public void addWriteOnlyColumn(String table, String column, String type) {
        writeOnlyColumnTypes.computeIfAbsent(table, k -> new HashMap<>()).put(column, type);
    }

    /**
     * A table the role may insert into but not read. It is not one of {@link #getTableNames()}, so no read
     * path sees it; only the insert does, through its write-only columns.
     */
    public void addWriteOnlyTable(String table) {
        writeOnlyTables.add(table);
    }

    public Set<String> getWriteOnlyTableNames() {
        return Collections.unmodifiableSet(writeOnlyTables);
    }

    public boolean isWriteOnlyTable(String table) {
        return writeOnlyTables.contains(table);
    }

    /** The readable tables, then the write-only ones: every table a mutation can name. */
    public Set<String> getMutableTableNames() {
        Set<String> tables = new LinkedHashSet<>(tableColumns.keySet());
        tables.addAll(writeOnlyTables);
        return tables;
    }

    public void setTableSchema(String table, String schema) {
        tableSchema.put(table, schema);
    }

    public void addColumnEnumType(String table, String column, String enumTypeName) {
        columnEnumType.put(table + "." + column, enumTypeName);
    }

    public void addForeignKey(String fromTable, String fromCol, String toTable, String toCol) {
        String fwdFieldName = fkColumnFieldName(fromTable, fromCol);
        forwardFks.put(fromTable + "." + fwdFieldName,
                new FkInfo(List.of(fromCol), toTable, List.of(toCol)));

        // Reverse FK: use child table name — unique across different child tables.
        // On collision (same child table, multiple FK columns to same parent), disambiguate.
        String baseRevName = fkFieldName(fromTable);
        String revKey = toTable + "." + baseRevName;
        if (reverseFks.containsKey(revKey)) {
            ReverseFkInfo existing = reverseFks.remove(revKey);
            String existingFkCol = existing.fkColumns().get(0);
            String existingSuffix = NamingUtils.capitalize(NamingUtils.toLowerCamelCase(existingFkCol));
            reverseFks.put(toTable + "." + baseRevName + existingSuffix, existing);
            String newSuffix = NamingUtils.capitalize(NamingUtils.toLowerCamelCase(fromCol));
            reverseFks.put(toTable + "." + baseRevName + newSuffix,
                    new ReverseFkInfo(fromTable, List.of(fromCol), List.of(toCol)));
        } else {
            reverseFks.put(revKey, new ReverseFkInfo(fromTable, List.of(fromCol), List.of(toCol)));
        }
    }

    public void addCompositeForeignKey(String fromTable, List<String> fromCols,
                                 String toTable, List<String> toCols) {
        String fwdFieldName = fkFieldName(toTable);
        forwardFks.put(fromTable + "." + fwdFieldName,
                new FkInfo(fromCols, toTable, toCols));
        String revFieldName = fkFieldName(fromTable);
        reverseFks.put(toTable + "." + revFieldName,
                new ReverseFkInfo(fromTable, fromCols, toCols));
    }

    /** Derive FK field name from the FK column name. No guessing, no stripping. */
    private String fkColumnFieldName(String tableKey, String fkColumn) {
        if (tableKey.contains(".")) {
            String schema = tableKey.substring(0, tableKey.indexOf('.'));
            return NamingUtils.schemaFieldName(schema, fkColumn);
        }
        return NamingUtils.toLowerCamelCase(fkColumn);
    }

    /** Derive FK field name from table key. Handles compound keys ("public.users" → "publicUsers"). */
    private String fkFieldName(String tableKey) {
        if (tableKey.contains(".")) {
            String schema = tableKey.substring(0, tableKey.indexOf('.'));
            String rawTable = tableKey.substring(tableKey.indexOf('.') + 1);
            return NamingUtils.schemaFieldName(schema, rawTable);
        }
        return NamingUtils.toLowerCamelCase(tableKey);
    }

    public void addView(String viewName) {
        viewNames.add(viewName);
    }

    public void addEnumValue(String enumTypeName, String label) {
        enumTypes.computeIfAbsent(enumTypeName, k -> new ArrayList<>()).add(label);
    }

    public void addFunction(String name, FunctionInfo info) {
        functions.put(name, info);
    }

    public void addCompositeTypeField(String typeName, String fieldName, String dataType) {
        compositeTypes.computeIfAbsent(typeName, k -> new ArrayList<>())
                .add(new CompositeTypeField(fieldName, dataType));
    }

    public void addComputedField(String tableName, String functionName, String returnType) {
        computedFields.computeIfAbsent(tableName, k -> new ArrayList<>())
                .add(new ComputedField(functionName, returnType));
    }

    // === Public getters ===

    public Set<String> getTableNames() { return Collections.unmodifiableSet(tableColumns.keySet()); }
    public boolean hasTable(String name) { return tableColumns.containsKey(name); }
    public String getTableSchema(String table) { return tableSchema.get(table); }

    /** True when tables from more than one schema are loaded. */
    public boolean isMultiSchema() {
        return new HashSet<>(tableSchema.values()).size() > 1;
    }

    /**
     * Resolve the schema for a table. Falls back to the given default if not explicitly set.
     */
    public String resolveSchema(String table, String defaultSchema) {
        String schema = tableSchema.get(table);
        return schema != null ? schema : defaultSchema;
    }
    public Set<String> getColumns(String table) { return tableColumns.getOrDefault(table, Set.of()); }
    public String getColumnType(String table, String col) {
        String type = columnTypes.getOrDefault(table, Map.of()).get(col);
        return type != null ? type : writeOnlyColumnTypes.getOrDefault(table, Map.of()).get(col);
    }
    public String getPrimaryKey(String table) {
        List<String> pks = primaryKeys.get(table);
        return (pks != null && !pks.isEmpty()) ? pks.getFirst() : "id";
    }
    public List<String> getPrimaryKeys(String table) {
        return primaryKeys.getOrDefault(table, List.of("id"));
    }
    /** True only when the table has explicitly declared primary key columns. */
    public boolean hasPrimaryKey(String table) {
        List<String> pks = primaryKeys.get(table);
        return pks != null && !pks.isEmpty();
    }
    public FkInfo getForwardFk(String table, String fieldName) { return forwardFks.get(table + "." + fieldName); }
    public Map<String, FkInfo> getAllForwardFks() { return Collections.unmodifiableMap(forwardFks); }
    public Map<String, ReverseFkInfo> getAllReverseFks() { return Collections.unmodifiableMap(reverseFks); }
    public ReverseFkInfo getReverseFk(String table, String fieldName) { return reverseFks.get(table + "." + fieldName); }
    public String getEnumType(String table, String column) { return columnEnumType.get(table + "." + column); }
    public List<ComputedField> getComputedFields(String table) { return computedFields.get(table); }
    public Map<String, List<String>> getEnumTypes() { return Collections.unmodifiableMap(enumTypes); }
    public boolean isView(String tableName) { return viewNames.contains(tableName); }
    public Set<String> getViewNames() { return Collections.unmodifiableSet(viewNames); }
    public Map<String, FunctionInfo> getFunctions() { return Collections.unmodifiableMap(functions); }
    public FunctionInfo getFunction(String name) { return functions.get(name); }
    public Map<String, List<CompositeTypeField>> getCompositeTypes() { return Collections.unmodifiableMap(compositeTypes); }
    public boolean isCompositeType(String typeName) { return compositeTypes.containsKey(typeName); }

    /** Records that a Postgres extension is installed. */
    public void addExtension(String name, String version) {
        if (name == null) return;
        extensions.put(name, version != null ? version : "");
    }

    /** True if the named extension was reported by pg_extension at introspection time. */
    public boolean hasExtension(String name) { return extensions.containsKey(name); }

    /** Version string for an installed extension, or null if absent. */
    public String getExtensionVersion(String name) { return extensions.get(name); }

    /** Read-only snapshot of all installed extensions (extname → extversion). */
    public Map<String, String> getExtensions() { return Collections.unmodifiableMap(extensions); }

    public record FkInfo(List<String> fkColumns, String refTable, List<String> refColumns) {
        /** Convenience for single-column FK */
        public String fkColumn() { return fkColumns.get(0); }
        public String refColumn() { return refColumns.get(0); }
        public boolean isComposite() { return fkColumns.size() > 1; }
    }
    public record ReverseFkInfo(String childTable, List<String> fkColumns, List<String> refColumns) {
        /** Convenience for single-column FK */
        public String fkColumn() { return fkColumns.get(0); }
        public String refColumn() { return refColumns.get(0); }
        public boolean isComposite() { return fkColumns.size() > 1; }
    }
    public record ComputedField(String functionName, String returnType) {}
    /** A routine as {@code pg_proc} reports it: a function or a procedure. */
    public enum RoutineKind { FUNCTION, PROCEDURE }

    public enum Volatility {
        IMMUTABLE, STABLE, VOLATILE;

        /** Maps {@code pg_proc.provolatile}; anything else is unknown (null). */
        public static Volatility fromCatalog(String code) {
            if (code == null) return null;
            return switch (code) {
                case "i" -> IMMUTABLE;
                case "s" -> STABLE;
                case "v" -> VOLATILE;
                default -> null;
            };
        }
    }

    /**
     * One declared argument. {@code mode} is {@code pg_proc.proargmodes}' letter: i (in), o (out),
     * b (inout), v (variadic), t (table column). {@code name} is empty for an unnamed argument.
     */
    public record FunctionArg(String name, String type, String mode, boolean hasDefault) {
        public boolean isInput() {
            return "i".equals(mode) || "b".equals(mode) || "v".equals(mode);
        }
    }

    /**
     * A reflected function or procedure. Plain reflection: whether it may be called is decided by
     * the access plan, from the project's tracked functions.
     *
     * @param returnTable the schema-qualified table or view whose row type it returns, or null
     * @param overloads   how many routines of the schema share this name
     */
    public record FunctionInfo(String schema, String name, RoutineKind kind, Volatility volatility,
                               boolean securityDefiner, boolean returnsSet, String returnTable,
                               List<FunctionArg> args, int overloads) {
        public FunctionInfo {
            args = List.copyOf(args);
        }

        public String qualifiedName() {
            return schema + "." + name;
        }
    }

    public record CompositeTypeField(String name, String type) {}
}
