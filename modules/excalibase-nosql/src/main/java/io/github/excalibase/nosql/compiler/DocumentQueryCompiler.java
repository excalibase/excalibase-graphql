package io.github.excalibase.nosql.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.nosql.model.CollectionInfo;
import io.github.excalibase.nosql.model.CollectionSchema;
import io.github.excalibase.nosql.model.IndexDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class DocumentQueryCompiler {

    private static final String NOSQL_SCHEMA = "nosql";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern IDENT_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static String safeIdent(String name, String context) {
        if (name == null || !IDENT_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + context + ": " + name);
        }
        return name;
    }

    private final CollectionInfo collectionInfo;

    public record CompiledDoc(String sql, Map<String, Object> params) {}

    public DocumentQueryCompiler(CollectionInfo collectionInfo) {
        this.collectionInfo = collectionInfo;
    }

    public CompiledDoc compileFind(String collection, Map<String, Object> filter, FindOptions opts) {
        var schema = resolveSchema(collection);
        var params = new LinkedHashMap<String, Object>();
        var sql = new StringBuilder();

        sql.append(selectClause());
        sql.append(" FROM ").append(qualifiedTable(collection));

        appendWhere(sql, filter, schema, params);
        appendOrderBy(sql, opts.sort());
        appendLimit(sql, params, opts.limit(), opts.offset());

        return new CompiledDoc(sql.toString(), params);
    }

    public CompiledDoc compileFindOne(String collection, Map<String, Object> filter) {
        var schema = resolveSchema(collection);
        var params = new LinkedHashMap<String, Object>();
        var sql = new StringBuilder();

        sql.append(selectClause());
        sql.append(" FROM ").append(qualifiedTable(collection));
        appendWhere(sql, filter, schema, params);
        sql.append(" LIMIT 1");

        return new CompiledDoc(sql.toString(), params);
    }

    public CompiledDoc compileGetById(String collection, String id) {
        var params = new LinkedHashMap<String, Object>();
        params.put("id", id);

        var sql = selectClause() +
                " FROM " + qualifiedTable(collection) +
                " WHERE id = :id::uuid LIMIT 1";

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileInsertOne(String collection, Map<String, Object> doc) {
        var params = new LinkedHashMap<String, Object>();
        params.put("data", toJson(doc));

        var sql = "INSERT INTO " + qualifiedTable(collection) + " (data)" +
                " VALUES (:data::jsonb)" +
                " RETURNING " + returningClause();

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
                " RETURNING " + returningClause();

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
        sql.append(" RETURNING ").append(returningClause());

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
                " RETURNING " + returningClause();

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileDeleteOne(String collection, Map<String, Object> filter) {
        var schema = resolveSchema(collection);
        var params = new LinkedHashMap<String, Object>();

        var sql = new StringBuilder();
        sql.append("DELETE FROM ").append(qualifiedTable(collection));
        appendWhere(sql, filter, schema, params);
        sql.append(" RETURNING ").append(returningClause());

        return new CompiledDoc(sql.toString(), params);
    }

    public CompiledDoc compileSearch(String collection, String query, int limit) {
        var schema = resolveSchema(collection);
        if (schema.searchField() == null) {
            throw new IllegalArgumentException("Collection '" + collection + "' has no search field configured");
        }
        var params = new LinkedHashMap<String, Object>();
        params.put("query", query);
        params.put("limit", Math.min(limit, 1000));

        var sql = selectClause() + ", ts_rank(search_text, websearch_to_tsquery(:query)) AS rank" +
                " FROM " + qualifiedTable(collection) +
                " WHERE search_text @@ websearch_to_tsquery(:query)" +
                " ORDER BY rank DESC" +
                " LIMIT :limit";

        return new CompiledDoc(sql, params);
    }

    public CompiledDoc compileVectorSearch(String collection, List<? extends Number> embedding, int topK) {
        var schema = resolveSchema(collection);
        if (schema.vector() == null) {
            throw new IllegalArgumentException("Collection '" + collection + "' has no vector field configured");
        }
        var params = new LinkedHashMap<String, Object>();
        params.put("embedding", embedding.toString());
        params.put("topK", Math.min(topK, 1000));

        var sql = selectClause() + ", embedding <=> :embedding::vector AS distance" +
                " FROM " + qualifiedTable(collection) +
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

    private String qualifiedTable(String collection) {
        return NOSQL_SCHEMA + ".\"" + safeIdent(collection, "collection name") + "\"";
    }

    private String selectClause() {
        return "SELECT id, data, created_at, updated_at";
    }

    private String returningClause() {
        return "id, data, created_at, updated_at";
    }

    @SuppressWarnings("unchecked")
    private void appendWhere(StringBuilder sql, Map<String, Object> filter,
                              CollectionSchema schema, Map<String, Object> params) {
        if (filter == null || filter.isEmpty()) return;

        var conditions = new java.util.ArrayList<String>();
        int paramIdx = params.size();

        for (var entry : filter.entrySet()) {
            String field = safeIdent(entry.getKey(), "filter field");
            Object value = entry.getValue();
            String indexType = findIndexType(schema, field);
            String colRef = buildColRef(field, indexType);

            if (value instanceof Map<?, ?> opMap) {
                for (var op : ((Map<String, Object>) opMap).entrySet()) {
                    String paramName = "p" + (paramIdx++);
                    String operator = switch (op.getKey()) {
                        case "$gt" -> ">";
                        case "$gte" -> ">=";
                        case "$lt" -> "<";
                        case "$lte" -> "<=";
                        case "$ne" -> "!=";
                        case "$in" -> "IN";
                        default -> "=";
                    };
                    if ("IN".equals(operator)) {
                        conditions.add(colRef + " IN (:" + paramName + ")");
                    } else {
                        conditions.add(colRef + " " + operator + " :" + paramName);
                    }
                    params.put(paramName, op.getValue());
                }
            } else {
                String paramName = "p" + (paramIdx++);
                conditions.add(colRef + " = :" + paramName);
                params.put(paramName, value instanceof Number ? value : String.valueOf(value));
            }
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }

    private void appendOrderBy(StringBuilder sql, Map<String, Object> sort) {
        if (sort == null || sort.isEmpty()) return;

        var orders = new java.util.ArrayList<String>();
        for (var entry : sort.entrySet()) {
            String field = safeIdent(entry.getKey(), "sort field");
            int direction = entry.getValue() instanceof Number n ? n.intValue() : 1;
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
