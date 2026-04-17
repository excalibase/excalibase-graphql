package io.github.excalibase.nosql.controller;

import io.github.excalibase.nosql.compiler.DocumentQueryCompiler;
import io.github.excalibase.nosql.compiler.FindOptions;
import io.github.excalibase.nosql.model.CollectionSchema;
import io.github.excalibase.nosql.schema.CollectionSchemaManager;
import io.github.excalibase.nosql.service.DocumentExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/nosql")
public class NoSqlController {

    private static final Set<String> RESERVED_PARAMS = Set.of(
            "limit", "offset", "sort", "allowScan", "search", "vector", "count");

    private final CollectionSchemaManager schemaManager;
    private final DocumentExecutionService executionService;

    public NoSqlController(CollectionSchemaManager schemaManager,
                           DocumentExecutionService executionService) {
        this.schemaManager = schemaManager;
        this.executionService = executionService;
    }

    private DocumentQueryCompiler compiler() {
        return new DocumentQueryCompiler(schemaManager.getCollectionInfo());
    }

    // ─── Schema ────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Object> syncSchema(@RequestBody Map<String, Object> schema) {
        var result = schemaManager.syncSchema(schema);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @GetMapping
    public ResponseEntity<Object> getSchema() {
        var names = schemaManager.getCollectionInfo().getCollectionNames();
        var collections = new LinkedHashMap<String, Object>();
        for (String name : names) {
            schemaManager.getCollectionInfo().getCollection(name).ifPresent(s ->
                    collections.put(name, Map.of(
                            "indexes", s.indexes(),
                            "indexedFields", s.indexedFields())));
        }
        return ResponseEntity.ok(Map.of("data", collections));
    }

    // ─── Read (GET — cacheable) ────────────────────────────────────────────────

    @GetMapping("/{collection}")
    public ResponseEntity<Object> find(@PathVariable String collection,
                                        @RequestParam Map<String, String> allParams) {
        var schema = resolveCollection(collection);

        // Special modes via query param
        if (allParams.containsKey("count")) {
            var filter = parseFilter(allParams, schema);
            var compiled = compiler().compileCount(collection, filter);
            long count = executionService.executeCount(compiled);
            return ResponseEntity.ok(Map.of("data", Map.of("count", count)));
        }

        if (allParams.containsKey("search")) {
            String query = allParams.get("search");
            int limit = toInt(allParams.get("limit"), 10);
            var compiled = compiler().compileSearch(collection, query, limit);
            var results = executionService.executeQuery(compiled);
            return ResponseEntity.ok(Map.of("data", results));
        }

        // Regular find
        var filter = parseFilter(allParams, schema);
        boolean allowScan = "true".equals(allParams.get("allowScan"));
        schema.validateQuery(filter.keySet(), allowScan);

        int limit = toInt(allParams.get("limit"), 30);
        int offset = toInt(allParams.get("offset"), 0);
        var sort = parseSort(allParams.get("sort"));

        var compiled = compiler().compileFind(collection, filter, new FindOptions(limit, offset, sort));
        var results = executionService.executeQuery(compiled);
        return ResponseEntity.ok(Map.of("data", results));
    }

    @GetMapping("/{collection}/{id}")
    public ResponseEntity<Object> getById(@PathVariable String collection,
                                           @PathVariable String id) {
        resolveCollection(collection);
        var compiled = compiler().compileGetById(collection, id);
        var result = executionService.executeSingleQuery(compiled);
        return result != null
                ? ResponseEntity.ok(Map.of("data", result))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
    }

    // ─── Write (POST/PATCH/DELETE) ─────────────────────────────────────────────

    @PostMapping("/{collection}")
    public ResponseEntity<Object> insert(@PathVariable String collection,
                                          @RequestParam(required = false) String vector,
                                          @RequestBody Map<String, Object> body) {
        resolveCollection(collection);

        // POST with ?vector=true → vector search
        if ("true".equals(vector)) {
            return handleVectorSearch(collection, body);
        }

        // Batch insert if "docs" array present
        if (body.containsKey("docs")) {
            @SuppressWarnings("unchecked")
            var docs = (List<Map<String, Object>>) body.get("docs");
            if (docs == null || docs.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "docs array required"));
            }
            var compiled = compiler().compileInsertMany(collection, docs);
            var results = executionService.executeBulkMutation(compiled);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", results));
        }

        // Single insert
        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) body.getOrDefault("doc", body);
        var compiled = compiler().compileInsertOne(collection, doc);
        var result = executionService.executeMutation(compiled);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", result));
    }

    @PatchMapping("/{collection}")
    public ResponseEntity<Object> update(@PathVariable String collection,
                                          @RequestParam Map<String, String> allParams,
                                          @RequestBody Map<String, Object> body) {
        var schema = resolveCollection(collection);
        var filter = parseFilter(allParams, schema);

        if (filter.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filter required in query params"));
        }

        schema.validateQuery(filter.keySet(), false);

        var compiled = compiler().compileUpdateMany(collection, filter, body);
        var results = executionService.executeBulkMutation(compiled);
        return ResponseEntity.ok(Map.of("data", results, "modified", results.size()));
    }

    @DeleteMapping("/{collection}")
    public ResponseEntity<Object> delete(@PathVariable String collection,
                                          @RequestParam Map<String, String> allParams) {
        var schema = resolveCollection(collection);
        var filter = parseFilter(allParams, schema);

        if (filter.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filter required in query params"));
        }

        schema.validateQuery(filter.keySet(), false);

        var compiled = compiler().compileDeleteMany(collection, filter);
        var results = executionService.executeBulkMutation(compiled);
        return ResponseEntity.ok(Map.of("data", results, "deleted", results.size()));
    }

    @DeleteMapping("/{collection}/{id}")
    public ResponseEntity<Object> deleteById(@PathVariable String collection,
                                              @PathVariable String id) {
        resolveCollection(collection);
        var compiled = compiler().compileDeleteById(collection, id);
        try {
            var result = executionService.executeMutation(compiled);
            return ResponseEntity.ok(Map.of("data", result));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Object> handleVectorSearch(String collection, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var embedding = (List<? extends Number>) body.get("embedding");
        if (embedding == null || embedding.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "embedding required"));
        }
        int topK = toInt(body.get("topK"), 5);
        var compiled = compiler().compileVectorSearch(collection, embedding, topK);
        var results = executionService.executeQuery(compiled);
        return ResponseEntity.ok(Map.of("data", results));
    }

    /**
     * Parse PostgREST-style filter params: status=eq.active, age=gt.25
     */
    private Map<String, Object> parseFilter(Map<String, String> params, CollectionSchema schema) {
        var filter = new LinkedHashMap<String, Object>();
        for (var entry : params.entrySet()) {
            String key = entry.getKey();
            if (RESERVED_PARAMS.contains(key)) continue;

            String value = entry.getValue();
            int dot = value.indexOf('.');
            if (dot > 0) {
                String op = value.substring(0, dot);
                String val = value.substring(dot + 1);
                switch (op) {
                    case "eq" -> filter.put(key, val);
                    case "neq" -> filter.put(key, Map.of("$ne", val));
                    case "gt" -> filter.put(key, Map.of("$gt", parseNumber(val)));
                    case "gte" -> filter.put(key, Map.of("$gte", parseNumber(val)));
                    case "lt" -> filter.put(key, Map.of("$lt", parseNumber(val)));
                    case "lte" -> filter.put(key, Map.of("$lte", parseNumber(val)));
                    case "in" -> filter.put(key, Map.of("$in", List.of(val.split(","))));
                    default -> filter.put(key, val);
                }
            } else {
                filter.put(key, value);
            }
        }
        return filter;
    }

    /**
     * Parse sort param: age.desc or age.asc,name.desc
     */
    private Map<String, Object> parseSort(String sort) {
        if (sort == null || sort.isBlank()) return null;
        var result = new LinkedHashMap<String, Object>();
        for (String part : sort.split(",")) {
            int dot = part.lastIndexOf('.');
            if (dot > 0) {
                String field = part.substring(0, dot);
                String dir = part.substring(dot + 1);
                result.put(field, "desc".equalsIgnoreCase(dir) ? -1 : 1);
            } else {
                result.put(part, 1);
            }
        }
        return result.isEmpty() ? null : result;
    }

    private Object parseNumber(String val) {
        try { return Integer.parseInt(val); } catch (NumberFormatException e) {
            try { return Double.parseDouble(val); } catch (NumberFormatException e2) { return val; }
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    private CollectionSchema resolveCollection(String collection) {
        return schemaManager.getCollectionInfo().getCollection(collection)
                .orElseThrow(() -> new NoSuchElementException("Unknown collection: " + collection));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Object> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
