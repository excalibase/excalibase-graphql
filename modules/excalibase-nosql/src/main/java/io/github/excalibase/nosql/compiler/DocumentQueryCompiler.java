package io.github.excalibase.nosql.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.nosql.model.CollectionInfo;
import io.github.excalibase.nosql.model.CollectionSchema;
import io.github.excalibase.nosql.model.IndexDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.nosql.schema.NoSqlIdentifiers.qualifiedTable;
import static io.github.excalibase.nosql.schema.NoSqlIdentifiers.safeIdent;

public class DocumentQueryCompiler {

    private static final String SELECT_CLAUSE = "SELECT id, data, created_at, updated_at";
    private static final String RETURNING_CLAUSE = "id, data, created_at, updated_at";
    private static final String SQL_RETURNING = " RETURNING ";
    private static final String SQL_FROM = " FROM ";
    private static final String MSG_COLLECTION_PREFIX = "Collection '";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CollectionInfo collectionInfo;

    public record CompiledDoc(String sql, Map<String, Object> params) {}

    public DocumentQueryCompiler(CollectionInfo collectionInfo) {
        this.collectionInfo = collectionInfo;
    }

    public CompiledDoc compileFind(String collection, Map<String, Object> filter, FindOptions opts) {
        var schema = resolveSchema(collection);
        var params = new LinkedHashMap<String, Object>();
        var sql = new StringBuilder();

        sql.append(SELECT_CLAUSE);
        sql.append(SQL_FROM).append(qualifiedTable(collection));

        appendWhere(sql, filter, schema, params);
        appendOrderBy(sql, opts.sort());
        appendLimit(sql, params, opts.limit(), opts.offset());

        return new CompiledDoc(sql.toString(), params);
    }

    public CompiledDoc compileFindOne(String collection, Map<String, Object> filter) {
        var schema = resolveSchema(collection);
        var params = new LinkedHashMap<String, Object>();
        var sql = new StringBuilder();

        sql.append(SELECT_CLAUSE);
        sql.append(SQL_FROM).append(qualifiedTable(collection));
        appendWhere(sql, filter, schema, params);
        sql.append(" LIMIT 1");

        return new CompiledDoc(sql.toString(), params);
    }

    public CompiledDoc compileGetById(String collection, String id) {
        var params = new LinkedHashMap<String, Object>();
        params.put("id", id);

        var sql = SELECT_CLAUSE +
                SQL_FROM + qualifiedTable(collection) +
                " WHERE id = :id::uuid LIMIT 1";

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileInsertOne(String collection, Map<String, Object> doc) {
        var params = new LinkedHashMap<String, Object>();
        params.put("data", toJson(doc));

        var sql = "INSERT INTO " + qualifiedTable(collection) + " (data)" +
                " VALUES (:data::jsonb)" +
                SQL_RETURNING + RETURNING_CLAUSE;

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileInsertMany(String collection, List<Map<String, Object>> docs) {
        var params = new LinkedHashMap<String, Object>();
        var values = new StringBuilder();

        for (int i = 0; i < docs.size(); i++) {
            String paramName = "data" + i;
            params.put(paramName, toJson(docs.get(i)));
            if (i > 0) values.append(", ");
            values.append("(:").append(paramName).append("::jsonb)");
        }

        var sql = "INSERT INTO " + qualifiedTable(collection) + " (data)" +
                " VALUES " + values +
                SQL_RETURNING + RETURNING_CLAUSE;

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileUpdateOne(String collection, Map<String, Object> filter, Map<String, Object> update) {
        var schema = resolveSchema(collection);
        var params = new LinkedHashMap<String, Object>();

        @SuppressWarnings("unchecked")
        var setFields = (Map<String, Object>) update.get("$set");
        if (setFields == null) {
            setFields = update;
        }
        params.put("patch", toJson(setFields));

        var sql = new StringBuilder();
        sql.append("UPDATE ").append(qualifiedTable(collection));
        sql.append(" SET data = data || :patch::jsonb, updated_at = clock_timestamp()");
        appendWhere(sql, filter, schema, params);
        sql.append(SQL_RETURNING).append(RETURNING_CLAUSE);

        return new CompiledDoc(sql.toString(), params);
    }

    public CompiledDoc compileUpdateMany(String collection, Map<String, Object> filter, Map<String, Object> update) {
        return compileUpdateOne(collection, filter, update);
    }

    public CompiledDoc compileDeleteMany(String collection, Map<String, Object> filter) {
        return compileDeleteOne(collection, filter);
    }

    public CompiledDoc compileDeleteById(String collection, String id) {
        var params = new LinkedHashMap<String, Object>();
        params.put("id", id);

        var sql = "DELETE FROM " + qualifiedTable(collection) +
                " WHERE id = :id::uuid" +
                SQL_RETURNING + RETURNING_CLAUSE;

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileDeleteOne(String collection, Map<String, Object> filter) {
        var schema = resolveSchema(collection);
        var params = new LinkedHashMap<String, Object>();

        var sql = new StringBuilder();
        sql.append("DELETE FROM ").append(qualifiedTable(collection));
        appendWhere(sql, filter, schema, params);
        sql.append(SQL_RETURNING).append(RETURNING_CLAUSE);

        return new CompiledDoc(sql.toString(), params);
    }

    public CompiledDoc compileSearch(String collection, String query, int limit) {
        var schema = resolveSchema(collection);
        if (schema.searchField() == null) {
            throw new IllegalArgumentException(MSG_COLLECTION_PREFIX + collection + "' has no search field configured");
        }
        var params = new LinkedHashMap<String, Object>();
        params.put("query", query);
        params.put("limit", Math.min(limit, 1000));

        var sql = SELECT_CLAUSE + ", ts_rank(search_text, websearch_to_tsquery(:query)) AS rank" +
                SQL_FROM + qualifiedTable(collection) +
                " WHERE search_text @@ websearch_to_tsquery(:query)" +
                " ORDER BY rank DESC" +
                " LIMIT :limit";

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileSetEmbedding(String collection, String id, List<? extends Number> embedding) {
        var schema = resolveSchema(collection);
        if (schema.vector() == null) {
            throw new IllegalArgumentException(MSG_COLLECTION_PREFIX + collection + "' has no vector field configured");
        }
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalArgumentException("embedding must be a non-empty numeric array");
        }
        var params = new LinkedHashMap<String, Object>();
        params.put("embedding", embedding.toString());
        params.put("id", id);

        var sql = "UPDATE " + qualifiedTable(collection) +
                " SET embedding = :embedding::vector, updated_at = clock_timestamp()" +
                " WHERE id = :id::uuid" +
                SQL_RETURNING + RETURNING_CLAUSE;

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileVectorSearch(String collection, List<? extends Number> embedding, int topK) {
        var schema = resolveSchema(collection);
        if (schema.vector() == null) {
            throw new IllegalArgumentException(MSG_COLLECTION_PREFIX + collection + "' has no vector field configured");
        }
        var params = new LinkedHashMap<String, Object>();
        params.put("embedding", embedding.toString());
        params.put("topK", Math.min(topK, 1000));

        var sql = SELECT_CLAUSE + ", embedding <=> :embedding::vector AS distance" +
                SQL_FROM + qualifiedTable(collection) +
                " ORDER BY embedding <=> :embedding::vector" +
                " LIMIT :topK";

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileCount(String collection, Map<String, Object> filter) {
        var schema = resolveSchema(collection);
        var params = new LinkedHashMap<String, Object>();

        var sql = new StringBuilder();
        sql.append("SELECT count(*) FROM ").append(qualifiedTable(collection));
        appendWhere(sql, filter, schema, params);

        return new CompiledDoc(sql.toString(), params);
    }

    private CollectionSchema resolveSchema(String collection) {
        return collectionInfo.getCollection(collection)
                .orElseThrow(() -> new IllegalArgumentException("Unknown collection: " + collection));
    }

    private void appendWhere(StringBuilder sql, Map<String, Object> filter,
                              CollectionSchema schema, Map<String, Object> params) {
        if (filter == null || filter.isEmpty()) return;

        var conditions = new java.util.ArrayList<String>();
        int[] paramIdx = { params.size() };

        for (var entry : filter.entrySet()) {
            String field = safeIdent(entry.getKey(), "filter field");
            String colRef = buildColRef(field, findIndexType(schema, field));
            appendFieldConditions(colRef, entry.getValue(), conditions, params, paramIdx);
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }

    /** Render conditions for a single field — a scalar equality or a map of operator/value pairs. */
    @SuppressWarnings("unchecked")
    private void appendFieldConditions(String colRef, Object value, List<String> conditions,
                                       Map<String, Object> params, int[] paramIdx) {
        if (value instanceof Map<?, ?> opMap) {
            for (var op : ((Map<String, Object>) opMap).entrySet()) {
                String paramName = "p" + (paramIdx[0]++);
                String operator = mongoOperatorToSql(op.getKey());
                if ("IN".equals(operator)) {
                    conditions.add(colRef + " IN (:" + paramName + ")");
                } else {
                    conditions.add(colRef + " " + operator + " :" + paramName);
                }
                params.put(paramName, op.getValue());
            }
            return;
        }
        String paramName = "p" + (paramIdx[0]++);
        conditions.add(colRef + " = :" + paramName);
        params.put(paramName, value instanceof Number ? value : String.valueOf(value));
    }

    private String mongoOperatorToSql(String mongoOp) {
        return switch (mongoOp) {
            case "$gt" -> ">";
            case "$gte" -> ">=";
            case "$lt" -> "<";
            case "$lte" -> "<=";
            case "$ne" -> "!=";
            case "$in" -> "IN";
            default -> "=";
        };
    }

    private void appendOrderBy(StringBuilder sql, Map<String, Object> sort) {
        if (sort == null || sort.isEmpty()) return;

        var orders = new java.util.ArrayList<String>();
        for (var entry : sort.entrySet()) {
            String field = safeIdent(entry.getKey(), "sort field");
            int direction = entry.getValue() instanceof Number number ? number.intValue() : 1;
            String dir = direction < 0 ? "DESC" : "ASC";
            orders.add("(data->>'" + field + "') " + dir);
        }
        sql.append(" ORDER BY ").append(String.join(", ", orders));
    }

    private void appendLimit(StringBuilder sql, Map<String, Object> params, int limit, int offset) {
        sql.append(" LIMIT :limit");
        params.put("limit", limit);
        if (offset > 0) {
            sql.append(" OFFSET :offset");
            params.put("offset", offset);
        }
    }

    private String findIndexType(CollectionSchema schema, String field) {
        for (IndexDef idx : schema.indexes()) {
            if (idx.fields().contains(field)) {
                return idx.type();
            }
        }
        return "string";
    }

    private String buildColRef(String field, String indexType) {
        return switch (indexType) {
            case "number", "numeric" -> "((data->>'" + field + "')::numeric)";
            case "boolean" -> "((data->>'" + field + "')::boolean)";
            default -> "(data->>'" + field + "')";
        };
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize document to JSON", e);
        }
    }
}
