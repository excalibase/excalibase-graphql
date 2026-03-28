package io.github.excalibase;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Loads table/column/FK metadata from PostgreSQL information_schema.
 * Lightweight — no ORM, no reflection framework.
 */
public class SchemaInfo {

    // table → Set<column_name>
    private final Map<String, Set<String>> tableColumns = new HashMap<>();
    // table → column → type
    private final Map<String, Map<String, String>> columnTypes = new HashMap<>();
    // table → primary key columns (ordered)
    private final Map<String, List<String>> primaryKeys = new HashMap<>();
    // table.fieldName → FkInfo (forward FK: fieldName = toLowerCamelCase(refTable))
    private final Map<String, FkInfo> forwardFks = new HashMap<>();
    // table.fieldName → ReverseFkInfo (reverse FK: fieldName = toLowerCamelCase(childTable) + "s")
    private final Map<String, ReverseFkInfo> reverseFks = new HashMap<>();
    // enum name → list of enum values
    private final Map<String, List<String>> enumTypes = new HashMap<>();
    // "table.column" → enum type name (e.g. "priority_level")
    private final Map<String, String> columnEnumType = new HashMap<>();
    // table → list of computed fields
    private final Map<String, List<ComputedField>> computedFields = new HashMap<>();
    // set of view names (read-only — no mutations)
    private final Set<String> viewNames = new HashSet<>();
    // stored procedures: name → ProcedureInfo
    private final Map<String, ProcedureInfo> storedProcedures = new LinkedHashMap<>();
    // composite types: typeName → list of fields
    private final Map<String, List<CompositeTypeField>> compositeTypes = new LinkedHashMap<>();

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
        loader.loadStoredProcedures(jdbc, schema, this);
    }

    public void clearAll() {
        tableColumns.clear();
        columnTypes.clear();
        primaryKeys.clear();
        forwardFks.clear();
        reverseFks.clear();
        enumTypes.clear();
        columnEnumType.clear();
        computedFields.clear();
        viewNames.clear();
        storedProcedures.clear();
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

    public void addColumnEnumType(String table, String column, String enumTypeName) {
        columnEnumType.put(table + "." + column, enumTypeName);
    }

    public void addForeignKey(String fromTable, String fromCol, String toTable, String toCol) {
        String fwdFieldName = NamingUtils.toLowerCamelCase(toTable);
        forwardFks.put(fromTable + "." + fwdFieldName,
                new FkInfo(List.of(fromCol), toTable, List.of(toCol)));
        String revFieldName = NamingUtils.toLowerCamelCase(fromTable);
        if (!revFieldName.endsWith("s")) revFieldName += "s";
        reverseFks.put(toTable + "." + revFieldName,
                new ReverseFkInfo(fromTable, List.of(fromCol), List.of(toCol)));
    }

    public void addCompositeForeignKey(String fromTable, List<String> fromCols,
                                 String toTable, List<String> toCols) {
        String fwdFieldName = NamingUtils.toLowerCamelCase(toTable);
        forwardFks.put(fromTable + "." + fwdFieldName,
                new FkInfo(fromCols, toTable, toCols));
        String revFieldName = NamingUtils.toLowerCamelCase(fromTable);
        if (!revFieldName.endsWith("s")) revFieldName += "s";
        reverseFks.put(toTable + "." + revFieldName,
                new ReverseFkInfo(fromTable, fromCols, toCols));
    }

    public void addView(String viewName) {
        viewNames.add(viewName);
    }

    public void addEnumValue(String enumTypeName, String label) {
        enumTypes.computeIfAbsent(enumTypeName, k -> new ArrayList<>()).add(label);
    }

    public void addStoredProcedure(String name, ProcedureInfo info) {
        storedProcedures.put(name, info);
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
    public Set<String> getColumns(String table) { return tableColumns.getOrDefault(table, Set.of()); }
    public String getColumnType(String table, String col) {
        return columnTypes.getOrDefault(table, Map.of()).get(col);
    }
    public String getPrimaryKey(String table) {
        List<String> pks = primaryKeys.get(table);
        return (pks != null && !pks.isEmpty()) ? pks.getFirst() : "id";
    }
    public List<String> getPrimaryKeys(String table) {
        return primaryKeys.getOrDefault(table, List.of("id"));
    }
    public FkInfo getForwardFk(String table, String fieldName) { return forwardFks.get(table + "." + fieldName); }
    public ReverseFkInfo getReverseFk(String table, String fieldName) { return reverseFks.get(table + "." + fieldName); }
    public String getEnumType(String table, String column) { return columnEnumType.get(table + "." + column); }
    public List<ComputedField> getComputedFields(String table) { return computedFields.get(table); }
    public Map<String, List<String>> getEnumTypes() { return Collections.unmodifiableMap(enumTypes); }
    public boolean isView(String tableName) { return viewNames.contains(tableName); }
    public Set<String> getViewNames() { return Collections.unmodifiableSet(viewNames); }
    public Map<String, ProcedureInfo> getStoredProcedures() { return Collections.unmodifiableMap(storedProcedures); }
    public Map<String, List<CompositeTypeField>> getCompositeTypes() { return Collections.unmodifiableMap(compositeTypes); }
    public boolean isCompositeType(String typeName) { return compositeTypes.containsKey(typeName); }

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
    public record ProcParam(String mode, String name, String type) {}
    public record ProcedureInfo(String name, List<ProcParam> params) {
        public List<ProcParam> inParams() {
            return params.stream().filter(p -> "IN".equals(p.mode()) || "INOUT".equals(p.mode())).toList();
        }
        public List<ProcParam> outParams() {
            return params.stream().filter(p -> "OUT".equals(p.mode()) || "INOUT".equals(p.mode())).toList();
        }
    }
    public record CompositeTypeField(String name, String type) {}
}
