package io.github.excalibase.nosql.schema;

import io.github.excalibase.nosql.model.*;
import io.github.excalibase.cdc.NatsCDCService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CollectionSchemaManager {

    private static final Logger log = LoggerFactory.getLogger(CollectionSchemaManager.class);
    private static final String NOSQL_SCHEMA = "nosql";
    private static final int MAX_INDEXES_PER_COLLECTION = 10;
    private static final Pattern EXPR_PATTERN = Pattern.compile(
            "data\\s*->>\\s*'([^']+)'");
    private static final Pattern CAST_PATTERN = Pattern.compile(
            "::(numeric|boolean|integer|int|float)");

    private final JdbcTemplate jdbc;
    private volatile CollectionInfo collectionInfo = new CollectionInfo();

    public CollectionSchemaManager(JdbcTemplate jdbc,
                                    @Autowired(required = false) NatsCDCService natsCDCService) {
        this.jdbc = jdbc;
        if (natsCDCService != null) {
            natsCDCService.setSchemaReloadCallback(this::reload);
        }
    }

    public void reload() {
        try {
            discoverCollections();
            log.info("NoSQL collections reloaded via CDC");
        } catch (Exception e) {
            log.error("NoSQL collection reload failed", e);
        }
    }

    @PostConstruct
    public void init() {
        try {
            discoverCollections();
        } catch (Exception e) {
            log.warn("Failed to discover NoSQL collections — starting empty", e);
        }
    }

    public CollectionInfo getCollectionInfo() {
        return collectionInfo;
    }

    public Map<String, Object> syncSchema(Map<String, Object> schemaRequest) {
        ensureSchema();

        @SuppressWarnings("unchecked")
        var collections = (Map<String, Map<String, Object>>) schemaRequest.get("collections");
        if (collections == null || collections.isEmpty()) {
            throw new IllegalArgumentException("Schema must contain at least one collection");
        }

        int created = 0;
        int updated = 0;

        for (var entry : collections.entrySet()) {
            String name = entry.getKey();
            var def = entry.getValue();

            @SuppressWarnings("unchecked")
            var indexDefs = (List<Map<String, Object>>) def.getOrDefault("indexes", List.of());
            if (indexDefs.size() > MAX_INDEXES_PER_COLLECTION) {
                throw new IllegalArgumentException(
                        "Collection '" + name + "' has " + indexDefs.size() +
                        " indexes, max is " + MAX_INDEXES_PER_COLLECTION);
            }

            boolean isNew = !tableExists(name);
            if (isNew) {
                createTable(name);
                created++;
            } else {
                updated++;
            }

            syncIndexes(name, indexDefs);

            String searchField = (String) def.get("search");
            if (searchField != null) {
                addSearchColumn(name, searchField);
            }

            @SuppressWarnings("unchecked")
            var vectorDef = (Map<String, Object>) def.get("vector");
            if (vectorDef != null) {
                addVectorColumn(name, (String) vectorDef.get("field"),
                        ((Number) vectorDef.get("dimensions")).intValue());
            }
        }

        discoverCollections();

        return Map.of("created", created, "updated", updated,
                       "collections", collectionInfo.getCollectionNames().size());
    }

    private void ensureSchema() {
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname = ?",
                Integer.class, NOSQL_SCHEMA);
        if (count != null && count > 0) {
            log.debug("Schema '{}' already exists", NOSQL_SCHEMA);
            return;
        }

        try {
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + NOSQL_SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot create '" + NOSQL_SCHEMA + "' schema. " +
                    "Ask your database admin to run: CREATE SCHEMA nosql; GRANT ALL ON SCHEMA nosql TO <user>;", e);
        }
    }

    private boolean tableExists(String collection) {
        var count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname = ? AND tablename = ?",
                Integer.class, NOSQL_SCHEMA, collection);
        return count != null && count > 0;
    }

    private void createTable(String collection) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + NOSQL_SCHEMA + ".\"" + collection + "\" (" +
                "id UUID PRIMARY KEY DEFAULT gen_random_uuid(), " +
                "data JSONB NOT NULL, " +
                "created_at TIMESTAMPTZ DEFAULT clock_timestamp(), " +
                "updated_at TIMESTAMPTZ DEFAULT clock_timestamp())");
        log.info("Created collection: {}", collection);
    }

    @SuppressWarnings("unchecked")
    private void syncIndexes(String collection, List<Map<String, Object>> declaredIndexes) {
        var existing = getExistingIndexes(collection);

        for (var idxDef : declaredIndexes) {
            var fields = (List<String>) idxDef.get("fields");
            String type = (String) idxDef.getOrDefault("type", "string");
            boolean unique = Boolean.TRUE.equals(idxDef.get("unique"));

            String indexName = (unique ? "uidx_" : "idx_") + collection + "_" + String.join("_", fields);

            if (!existing.containsKey(indexName)) {
                createExpressionIndex(collection, indexName, fields, type, unique);
            }
            existing.remove(indexName);
        }

        for (String orphan : existing.keySet()) {
            if (orphan.startsWith("idx_") || orphan.startsWith("uidx_")) {
                jdbc.execute("DROP INDEX IF EXISTS " + NOSQL_SCHEMA + ".\"" + orphan + "\"");
                log.info("Dropped orphan index: {}", orphan);
            }
        }
    }

    private void createExpressionIndex(String collection, String indexName,
                                        List<String> fields, String type, boolean unique) {
        var exprs = new ArrayList<String>();
        var predicates = new ArrayList<String>();

        for (String field : fields) {
            String expr = switch (type) {
                case "number", "numeric" -> "((data->>'" + field + "')::numeric)";
                case "boolean" -> "((data->>'" + field + "')::boolean)";
                default -> "(data->>'" + field + "')";
            };
            exprs.add(expr);
            predicates.add("(data->>'" + field + "') IS NOT NULL");
        }

        String sql = "CREATE " + (unique ? "UNIQUE " : "") + "INDEX IF NOT EXISTS \"" + indexName + "\"" +
                " ON " + NOSQL_SCHEMA + ".\"" + collection + "\" (" + String.join(", ", exprs) + ")" +
                " WHERE " + String.join(" AND ", predicates);

        jdbc.execute(sql);
        log.info("Created index: {}", indexName);
    }

    private Map<String, String> getExistingIndexes(String collection) {
        var indexes = new LinkedHashMap<String, String>();
        jdbc.query(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
                rs -> {
                    String name = rs.getString("indexname");
                    if (!name.endsWith("_pkey")) {
                        indexes.put(name, rs.getString("indexdef"));
                    }
                },
                NOSQL_SCHEMA, collection);
        return indexes;
    }

    private void addSearchColumn(String collection, String field) {
        String table = NOSQL_SCHEMA + ".\"" + collection + "\"";
        try {
            jdbc.execute("ALTER TABLE " + table +
                    " ADD COLUMN IF NOT EXISTS search_text tsvector" +
                    " GENERATED ALWAYS AS (to_tsvector('english', coalesce(data->>'" + field + "', ''))) STORED");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_" + collection + "_search ON " + table + " USING gin(search_text)");
            log.info("Added search column for field '{}' on collection '{}'", field, collection);
        } catch (Exception e) {
            log.warn("Failed to add search column on {}: {}", collection, e.getMessage());
        }
    }

    private void addVectorColumn(String collection, String field, int dimensions) {
        String table = NOSQL_SCHEMA + ".\"" + collection + "\"";
        try {
            jdbc.execute("ALTER TABLE " + table +
                    " ADD COLUMN IF NOT EXISTS embedding vector(" + dimensions + ")");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_" + collection + "_vector ON " + table +
                    " USING hnsw(embedding vector_cosine_ops)");
            log.info("Added vector column ({} dims) on collection '{}'", dimensions, collection);
        } catch (Exception e) {
            log.warn("Failed to add vector column on {}: {}", collection, e.getMessage());
        }
    }

    private void discoverCollections() {
        var newInfo = new CollectionInfo();

        jdbc.query(
                "SELECT tablename FROM pg_tables WHERE schemaname = ?",
                rs -> {
                    String name = rs.getString("tablename");
                    var indexes = parseIndexes(name);
                    var indexedFields = new LinkedHashSet<String>();
                    for (var idx : indexes) {
                        indexedFields.addAll(idx.fields());
                    }
                    String searchField = detectSearchField(name);
                    VectorDef vectorDef = detectVectorDef(name);
                    var schema = new CollectionSchema(name, Map.of(), indexes, indexedFields, searchField, vectorDef);
                    newInfo.addCollection(name, schema);
                },
                NOSQL_SCHEMA);

        collectionInfo = newInfo;
        log.info("Discovered {} NoSQL collections", newInfo.getCollectionNames().size());
    }

    private String detectSearchField(String collection) {
        try {
            var count = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = 'search_text'",
                    Integer.class, NOSQL_SCHEMA, collection);
            return (count != null && count > 0) ? "search_text" : null;
        } catch (Exception e) {
            return null;
        }
    }

    private VectorDef detectVectorDef(String collection) {
        try {
            var count = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = 'embedding'",
                    Integer.class, NOSQL_SCHEMA, collection);
            if (count != null && count > 0) {
                return new VectorDef("embedding", 0);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private List<IndexDef> parseIndexes(String collection) {
        var indexes = new ArrayList<IndexDef>();

        jdbc.query(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
                rs -> {
                    String name = rs.getString("indexname");
                    String def = rs.getString("indexdef");
                    if (name.endsWith("_pkey")) return;

                    boolean unique = name.startsWith("uidx_");
                    Matcher m = EXPR_PATTERN.matcher(def);
                    var fieldSet = new LinkedHashSet<String>();
                    String type = "string";
                    while (m.find()) {
                        fieldSet.add(m.group(1));
                    }
                    var fields = new ArrayList<>(fieldSet);
                    Matcher castMatcher = CAST_PATTERN.matcher(def);
                    if (castMatcher.find()) {
                        type = castMatcher.group(1);
                    }
                    if (!fields.isEmpty()) {
                        indexes.add(new IndexDef(fields, type, unique));
                    }
                },
                NOSQL_SCHEMA, collection);

        return indexes;
    }
}
