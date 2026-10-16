package io.github.excalibase.schema;

import io.github.excalibase.spi.SchemaLoader;
import io.github.excalibase.spi.SqlEngineFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

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
    // table.fieldName → ReverseFkInfo (reverse FK: fieldName = the child table's, see addReverseFk)
    private final Map<String, ReverseFkInfo> reverseFks = new HashMap<>();
    // table.childFieldName → every key of that child table pointing at the table, which together decide the names
    private final Map<String, List<ReverseFkInfo>> reverseFkGroups = new HashMap<>();
    // a role's relationships a nested insert may go through, keyed like the FKs above; unset = every FK
    private Map<String, ReverseFkInfo> arrayInsertRelationships;
    private Map<String, FkInfo> objectInsertRelationships;
    // the second name of a relationship whose own name is its key column's ("publicHomeTeamId"); derived
    // from the FKs and columns above on first use, dropped whenever they change
    private final AtomicReference<RelationshipAliases> relationshipAliases = new AtomicReference<>();
    // the schema whose relationships were copied, whose aliases this one keeps (see copyRelationships)
    private SchemaInfo aliasSource;
    // enum name → list of enum values
    private final Map<String, List<String>> enumTypes = new HashMap<>();
    // "table.column" → enum type name (e.g. "priority_level")
    private final Map<String, String> columnEnumType = new HashMap<>();
    // "table.column" of text columns the database can search by full text in place (a MySQL FULLTEXT index on it alone)
    private final Set<String> fullTextColumns = new HashSet<>();
    // table → column → the database's own declaration, where the reported type loses detail (MySQL)
    private final Map<String, Map<String, ColumnDeclaration>> columnDeclarations = new HashMap<>();
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
        relationshipAliases.set(null);
        tableSchema.remove(table);
        Set<String> cols = tableColumns.remove(table);
        columnTypes.remove(table);
        columnDeclarations.remove(table);
        writeOnlyColumnTypes.remove(table);
        writeOnlyTables.remove(table);
        primaryKeys.remove(table);
        viewNames.remove(table);
        computedFields.remove(table);
        // Clean column enum type entries
        if (cols != null) {
            for (String col : cols) {
                columnEnumType.remove(table + "." + col);
                fullTextColumns.remove(table + "." + col);
            }
        }
    }

    public void clearAll() {
        relationshipAliases.set(null);
        tableSchema.clear();
        tableColumns.clear();
        columnTypes.clear();
        columnDeclarations.clear();
        writeOnlyColumnTypes.clear();
        writeOnlyTables.clear();
        primaryKeys.clear();
        forwardFks.clear();
        reverseFks.clear();
        reverseFkGroups.clear();
        aliasSource = null;
        arrayInsertRelationships = null;
        objectInsertRelationships = null;
        enumTypes.clear();
        columnEnumType.clear();
        fullTextColumns.clear();
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
        relationshipAliases.set(null);
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

    public void addColumnDeclaration(String table, String column, ColumnDeclaration declaration) {
        columnDeclarations.computeIfAbsent(table, k -> new HashMap<>()).put(column, declaration);
    }

    public void addColumnEnumType(String table, String column, String enumTypeName) {
        columnEnumType.put(table + "." + column, enumTypeName);
    }

    public void addFullTextColumn(String table, String column) {
        fullTextColumns.add(table + "." + column);
    }

    public void addForeignKey(String fromTable, String fromCol, String toTable, String toCol) {
        relationshipAliases.set(null);
        String fwdFieldName = fkColumnFieldName(fromTable, fromCol);
        forwardFks.put(fromTable + "." + fwdFieldName,
                new FkInfo(List.of(fromCol), toTable, List.of(toCol)));
        addReverseFk(toTable, new ReverseFkInfo(fromTable, List.of(fromCol), List.of(toCol)));
    }

    public void addCompositeForeignKey(String fromTable, List<String> fromCols,
                                 String toTable, List<String> toCols) {
        relationshipAliases.set(null);
        String fwdFieldName = fkFieldName(toTable);
        forwardFks.put(fromTable + "." + fwdFieldName,
                new FkInfo(fromCols, toTable, toCols));
        addReverseFk(toTable, new ReverseFkInfo(fromTable, fromCols, toCols));
    }

    /**
     * An array relationship is named after its child table; when several keys of one child table point at
     * the same table, every one of them is also named after its key columns. The names depend only on
     * which keys exist, never on the order they are added in.
     */
    private void addReverseFk(String toTable, ReverseFkInfo relation) {
        String baseKey = toTable + "." + fkFieldName(relation.childTable());
        List<ReverseFkInfo> group = reverseFkGroups.computeIfAbsent(baseKey, k -> new ArrayList<>());
        if (group.contains(relation)) {
            return;
        }
        group.forEach(member -> reverseFks.remove(reverseFkKey(baseKey, member, group.size() > 1)));
        group.add(relation);
        group.forEach(member -> reverseFks.put(reverseFkKey(baseKey, member, group.size() > 1), member));
    }

    private static String reverseFkKey(String baseKey, ReverseFkInfo relation, boolean shared) {
        if (!shared) {
            return baseKey;
        }
        StringBuilder key = new StringBuilder(baseKey);
        relation.fkColumns().forEach(column ->
                key.append(NamingUtils.capitalize(NamingUtils.toLowerCamelCase(column))));
        return key.toString();
    }

    /**
     * Takes the relationships of {@code source} that {@code keep} accepts (given the referencing table and
     * the key), under the names and aliases {@code source} gave them, so a filtered copy of a schema names
     * every relationship it keeps exactly as the whole schema does. Meant for a schema with no keys of its own.
     */
    public void copyRelationships(SchemaInfo source, BiPredicate<String, FkInfo> keep) {
        relationshipAliases.set(null);
        aliasSource = source;
        source.forwardFks.forEach((key, fk) -> {
            if (keep.test(owner(key), fk)) {
                forwardFks.put(key, fk);
            }
        });
        source.reverseFks.forEach((key, relation) -> {
            if (keep.test(relation.childTable(), new FkInfo(relation.fkColumns(), owner(key), relation.refColumns()))) {
                reverseFks.put(key, relation);
            }
        });
    }

    /** A relationship key is {@code <table>.<field>}; the table is everything before the last dot. */
    private static String owner(String key) {
        return key.substring(0, key.lastIndexOf('.'));
    }

    /** Derive FK field name from the FK column name. No guessing, no stripping. */
    static String fkColumnFieldName(String tableKey, String fkColumn) {
        if (tableKey.contains(".")) {
            String schema = tableKey.substring(0, tableKey.indexOf('.'));
            return NamingUtils.schemaFieldName(schema, fkColumn);
        }
        return NamingUtils.toLowerCamelCase(fkColumn);
    }

    /** Derive FK field name from table key. Handles compound keys ("public.users" → "publicUsers"). */
    static String fkFieldName(String tableKey) {
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
    /** The object relationship {@code fieldName} of {@code table}, by its own name or its alias. */
    public FkInfo getForwardFk(String table, String fieldName) {
        return forwardFks.get(relationshipKey(table, fieldName));
    }
    public Map<String, FkInfo> getAllForwardFks() { return Collections.unmodifiableMap(forwardFks); }
    public Map<String, ReverseFkInfo> getAllReverseFks() { return Collections.unmodifiableMap(reverseFks); }
    /** The array relationship {@code fieldName} of {@code table}, by its own name or its alias. */
    public ReverseFkInfo getReverseFk(String table, String fieldName) {
        return reverseFks.get(relationshipKey(table, fieldName));
    }

    /**
     * The name a relationship is better known by, or null when it has none: an object relationship named
     * after its key column is also named without the column's {@code _id} ({@code publicHomeTeamId} →
     * {@code publicHomeTeam}), and one of several array relationships from the same table is also named by
     * its key ({@code publicMatchesHomeTeamId} → {@code publicMatchesByHomeTeam}). The first name keeps
     * working; it is the deprecated one.
     */
    public String getRelationshipAlias(String table, String fieldName) {
        String alias = relationshipAliases().byCanonical().get(table + "." + fieldName);
        return alias == null ? null : alias.substring(table.length() + 1);
    }

    /** {@code table.fieldName}, with an alias replaced by the relationship's own name. */
    private String relationshipKey(String table, String fieldName) {
        String key = table + "." + fieldName;
        if (forwardFks.containsKey(key) || reverseFks.containsKey(key)) {
            return key;
        }
        return relationshipAliases().byAlias().getOrDefault(key, key);
    }

    private RelationshipAliases relationshipAliases() {
        RelationshipAliases current = relationshipAliases.get();
        if (current == null) {
            current = aliasSource == null
                    ? RelationshipAliases.of(this)
                    : aliasSource.relationshipAliases().within(this);
            relationshipAliases.set(current);
        }
        return current;
    }
    public String getEnumType(String table, String column) { return columnEnumType.get(table + "." + column); }
    public boolean isFullTextColumn(String table, String column) { return fullTextColumns.contains(table + "." + column); }

    /** How the database declares {@code column}; null where the loader records nothing beyond its type. */
    public ColumnDeclaration getColumnDeclaration(String table, String column) {
        return columnDeclarations.getOrDefault(table, Map.of()).get(column);
    }

    /**
     * Fixes the relationships a nested insert may go through, which a role holds by its insert
     * permissions rather than its select ones. Until this is called they are every foreign key.
     */
    public void setInsertRelationships(Map<String, ReverseFkInfo> arrays, Map<String, FkInfo> objects) {
        arrayInsertRelationships = Map.copyOf(arrays);
        objectInsertRelationships = Map.copyOf(objects);
    }

    /** Array relationships ({@code table.field} → the child table) a nested insert may go through. */
    public Map<String, ReverseFkInfo> getArrayInsertRelationships() {
        return arrayInsertRelationships != null ? arrayInsertRelationships : getAllReverseFks();
    }

    /** Object relationships ({@code table.field} → the referenced table) a nested insert may go through. */
    public Map<String, FkInfo> getObjectInsertRelationships() {
        return objectInsertRelationships != null ? objectInsertRelationships : getAllForwardFks();
    }

    public ReverseFkInfo getArrayInsertRelationship(String table, String fieldName) {
        return getArrayInsertRelationships().get(relationshipKey(table, fieldName));
    }

    public FkInfo getObjectInsertRelationship(String table, String fieldName) {
        return getObjectInsertRelationships().get(relationshipKey(table, fieldName));
    }
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

    /**
     * A column as the database declares it ({@code tinyint(1)}, {@code int unsigned}, {@code enum('a','b')}),
     * with the character set and collation its text compares under; both null for a non-text column.
     */
    public record ColumnDeclaration(String columnType, String charset, String collation) {}
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
