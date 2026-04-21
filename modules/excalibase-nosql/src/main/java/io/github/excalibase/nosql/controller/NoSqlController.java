package io.github.excalibase.nosql.controller;

import io.github.excalibase.nosql.compiler.DocumentQueryCompiler;
import io.github.excalibase.nosql.compiler.FindOptions;
import io.github.excalibase.nosql.model.CollectionSchema;
import io.github.excalibase.nosql.schema.CollectionSchemaManager;
import io.github.excalibase.nosql.service.DocumentExecutionService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/nosql")
public class NoSqlController {

    private static final String KEY_ERROR = "error";
    private static final String MSG_NOT_FOUND = "Not found";
    private static final String PARAM_SEARCH = "search";
    private static final String PARAM_LIMIT = "limit";
    private static final String PARAM_COUNT = "count";

    private static final Set<String> RESERVED_PARAMS = Set.of(
            PARAM_LIMIT, "offset", "sort", PARAM_SEARCH, "vector", PARAM_COUNT, "stats");

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
            schemaManager.getCollectionInfo().getCollection(name).ifPresent(schema ->
                    collections.put(name, Map.of(
                            "indexes", schema.indexes(),
                            "indexedFields", schema.indexedFields())));
        }
        return ResponseEntity.ok(Map.of("data", collections));
    }

    // ─── Read (GET — cacheable) ────────────────────────────────────────────────

    @GetMapping("/{collection}")
    public ResponseEntity<Object> find(@PathVariable String collection,
                                        @RequestParam Map<String, String> allParams,
                                        @RequestHeader(value = "X-Debug", required = false) String debug) {
        var schema = resolveCollection(collection);

        // Stats mode
        if (allParams.containsKey("stats")) {
            var stats = schemaManager.getCollectionStats(collection);
            return ResponseEntity.ok(Map.of("data", stats));
        }

        // Count mode
        if (allParams.containsKey(PARAM_COUNT)) {
            var filter = parseFilter(allParams);
            var compiled = compiler().compileCount(collection, filter);
            long count = executionService.executeCount(compiled);
            return ResponseEntity.ok(Map.of("data", Map.of(PARAM_COUNT, count)));
        }

        // Search mode
        if (allParams.containsKey(PARAM_SEARCH)) {
            String query = allParams.get(PARAM_SEARCH);
            int limit = toInt(allParams.get(PARAM_LIMIT), 10);
            var compiled = compiler().compileSearch(collection, query, limit);
            var results = executionService.executeQuery(compiled);
            return ResponseEntity.ok(Map.of("data", results));
        }

        // Regular find — never rejects, warns via headers in debug mode
        var filter = parseFilter(allParams);
        var warnings = schema.checkIndexes(filter.keySet());

        int limit = toInt(allParams.get(PARAM_LIMIT), 30);
        int offset = toInt(allParams.get("offset"), 0);
        var sort = parseSort(allParams.get("sort"));

        long startTime = System.nanoTime();
        var compiled = compiler().compileFind(collection, filter, new FindOptions(limit, offset, sort));
        var results = executionService.executeQuery(compiled);
        long queryTimeMs = (System.nanoTime() - startTime) / 1_000_000;

        var headers = new HttpHeaders();
        if ("true".equals(debug)) {
            headers.add("X-Query-Time", queryTimeMs + "ms");
            if (!warnings.isEmpty()) {
                for (String warning : warnings) {
                    headers.add("X-Warning", warning);
                }
            }
        }

        return ResponseEntity.ok().headers(headers).body(Map.of("data", results));
    }

    @GetMapping("/{collection}/{id}")
    public ResponseEntity<Object> getById(@PathVariable String collection,
                                           @PathVariable String id) {
        resolveCollection(collection);
        var compiled = compiler().compileGetById(collection, id);
        return executionService.executeSingleQuery(compiled)
                .<ResponseEntity<Object>>map(result -> ResponseEntity.ok(Map.of("data", result)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(KEY_ERROR, MSG_NOT_FOUND)));
    }

    // ─── Write (POST/PATCH/DELETE) ─────────────────────────────────────────────

    @PostMapping("/{collection}")
    public ResponseEntity<Object> insert(@PathVariable String collection,
                                          @RequestParam(required = false) String vector,
                                          @RequestBody Map<String, Object> body) {
        resolveCollection(collection);

        if ("true".equals(vector)) {
            return handleVectorSearch(collection, body);
        }

        if (body.containsKey("docs")) {
            if (!(body.get("docs") instanceof List<?> rawList) || rawList.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "docs array required"));
            }
            var docs = new ArrayList<Map<String, Object>>();
            for (Object item : rawList) {
                if (!(item instanceof Map<?, ?> itemMap)) {
                    return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "docs must be array of objects"));
                }
                @SuppressWarnings("unchecked")
                var doc = (Map<String, Object>) itemMap;
                docs.add(doc);
            }
            var compiled = compiler().compileInsertMany(collection, docs);
            var results = executionService.executeBulkMutation(compiled);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", results));
        }

        Object rawDoc = body.getOrDefault("doc", body);
        if (!(rawDoc instanceof Map<?, ?>)) {
            return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "doc must be an object"));
        }
        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) rawDoc;
        var compiled = compiler().compileInsertOne(collection, doc);
        var result = executionService.executeMutation(compiled);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", result));
    }

    @PatchMapping("/{collection}")
    public ResponseEntity<Object> update(@PathVariable String collection,
                                          @RequestParam Map<String, String> allParams,
                                          @RequestBody Map<String, Object> body) {
        resolveCollection(collection);
        var filter = parseFilter(allParams);

        if (filter.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "filter required in query params"));
        }

        var compiled = compiler().compileUpdateMany(collection, filter, body);
        var results = executionService.executeBulkMutation(compiled);
        return ResponseEntity.ok(Map.of("data", results, "modified", results.size()));
    }

    @DeleteMapping("/{collection}")
    public ResponseEntity<Object> delete(@PathVariable String collection,
                                          @RequestParam Map<String, String> allParams) {
        resolveCollection(collection);
        var filter = parseFilter(allParams);

        if (filter.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "filter required in query params"));
        }

        var compiled = compiler().compileDeleteMany(collection, filter);
        var results = executionService.executeBulkMutation(compiled);
        return ResponseEntity.ok(Map.of("data", results, "deleted", results.size()));
    }

    @PutMapping("/{collection}/{id}/embedding")
    public ResponseEntity<Object> setEmbedding(@PathVariable String collection,
                                                @PathVariable String id,
                                                @RequestBody Map<String, Object> body) {
        resolveCollection(collection);
        if (!(body.get("embedding") instanceof List<?> rawList) || rawList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "embedding must be a non-empty numeric array"));
        }
        var embedding = new ArrayList<Number>(rawList.size());
        for (Object item : rawList) {
            if (!(item instanceof Number number)) {
                return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "embedding values must be numeric"));
            }
            embedding.add(number);
        }
        try {
            var compiled = compiler().compileSetEmbedding(collection, id, embedding);
            var result = executionService.executeMutation(compiled);
            return ResponseEntity.ok(Map.of("data", result));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(KEY_ERROR, MSG_NOT_FOUND));
        }
    }

    @DeleteMapping("/{collection}/{id}")
    public ResponseEntity<Object> deleteById(@PathVariable String collection,
                                              @PathVariable String id) {
        resolveCollection(collection);
        var compiled = compiler().compileDeleteById(collection, id);
        try {
            var result = executionService.executeMutation(compiled);
            return ResponseEntity.ok(Map.of("data", result));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(KEY_ERROR, MSG_NOT_FOUND));
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Object> handleVectorSearch(String collection, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var embedding = (List<? extends Number>) body.get("embedding");
        if (embedding == null || embedding.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "embedding required"));
        }
        int topK = toInt(body.get("topK"), 5);
        var compiled = compiler().compileVectorSearch(collection, embedding, topK);
        var results = executionService.executeQuery(compiled);
        return ResponseEntity.ok(Map.of("data", results));
    }

    private Map<String, Object> parseFilter(Map<String, String> params) {
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

    private Map<String, Object> parseSort(String sort) {
        if (sort == null || sort.isBlank()) return Map.of();
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
        return result;
    }

    private Object parseNumber(String val) {
        try { return Integer.parseInt(val); } catch (NumberFormatException e) {
            try { return Double.parseDouble(val); } catch (NumberFormatException e2) { return val; }
        }
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String stringValue) {
            try { return Integer.parseInt(stringValue); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    private CollectionSchema resolveCollection(String collection) {
        return schemaManager.getCollectionInfo().getCollection(collection)
                .orElseThrow(() -> new NoSuchElementException("Unknown collection: " + collection));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Object> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(KEY_ERROR, e.getMessage()));
    }
}
