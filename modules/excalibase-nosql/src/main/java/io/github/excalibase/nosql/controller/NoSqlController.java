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

    @PostMapping("/_schema")
    public ResponseEntity<Object> syncSchema(@RequestBody Map<String, Object> schema) {
        var result = schemaManager.syncSchema(schema);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @GetMapping("/_schema")
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

    @PostMapping("/{collection}/find")
    public ResponseEntity<Object> find(@PathVariable String collection,
                                        @RequestBody Map<String, Object> body) {
        var schema = resolveCollection(collection);
        @SuppressWarnings("unchecked")
        var filter = (Map<String, Object>) body.getOrDefault("filter", Map.of());
        boolean allowScan = Boolean.TRUE.equals(body.get("allowScan"));

        schema.validateQuery(filter.keySet(), allowScan);

        int limit = toInt(body.get("limit"), 30);
        int offset = toInt(body.get("offset"), 0);
        @SuppressWarnings("unchecked")
        var sort = (Map<String, Object>) body.get("sort");

        var compiled = compiler().compileFind(collection, filter, new FindOptions(limit, offset, sort));
        var results = executionService.executeQuery(compiled);
        return ResponseEntity.ok(Map.of("data", results));
    }

    @PostMapping("/{collection}/findOne")
    public ResponseEntity<Object> findOne(@PathVariable String collection,
                                           @RequestBody Map<String, Object> body) {
        var schema = resolveCollection(collection);
        @SuppressWarnings("unchecked")
        var filter = (Map<String, Object>) body.getOrDefault("filter", Map.of());
        boolean allowScan = Boolean.TRUE.equals(body.get("allowScan"));

        schema.validateQuery(filter.keySet(), allowScan);

        var compiled = compiler().compileFindOne(collection, filter);
        var result = executionService.executeSingleQuery(compiled);
        return result != null
                ? ResponseEntity.ok(Map.of("data", result))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
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

    @PostMapping("/{collection}/insertOne")
    public ResponseEntity<Object> insertOne(@PathVariable String collection,
                                             @RequestBody Map<String, Object> body) {
        resolveCollection(collection);
        @SuppressWarnings("unchecked")
        var doc = (Map<String, Object>) body.getOrDefault("doc", body);
        var compiled = compiler().compileInsertOne(collection, doc);
        var result = executionService.executeMutation(compiled);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", result));
    }

    @PostMapping("/{collection}/insertMany")
    public ResponseEntity<Object> insertMany(@PathVariable String collection,
                                              @RequestBody Map<String, Object> body) {
        resolveCollection(collection);
        @SuppressWarnings("unchecked")
        var docs = (List<Map<String, Object>>) body.get("docs");
        if (docs == null || docs.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "docs array required"));
        }
        var compiled = compiler().compileInsertMany(collection, docs);
        var results = executionService.executeBulkMutation(compiled);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", results));
    }

    @PostMapping("/{collection}/updateOne")
    public ResponseEntity<Object> updateOne(@PathVariable String collection,
                                             @RequestBody Map<String, Object> body) {
        var schema = resolveCollection(collection);
        @SuppressWarnings("unchecked")
        var filter = (Map<String, Object>) body.get("filter");
        @SuppressWarnings("unchecked")
        var update = (Map<String, Object>) body.get("update");

        if (filter == null || filter.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filter required"));
        }
        if (update == null || update.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "update required"));
        }

        schema.validateQuery(filter.keySet(), false);

        var compiled = compiler().compileUpdateOne(collection, filter, update);
        try {
            var result = executionService.executeMutation(compiled);
            return ResponseEntity.ok(Map.of("data", result));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No matching document"));
        }
    }

    @PostMapping("/{collection}/updateMany")
    public ResponseEntity<Object> updateMany(@PathVariable String collection,
                                              @RequestBody Map<String, Object> body) {
        var schema = resolveCollection(collection);
        @SuppressWarnings("unchecked")
        var filter = (Map<String, Object>) body.get("filter");
        @SuppressWarnings("unchecked")
        var update = (Map<String, Object>) body.get("update");

        if (filter == null || filter.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filter required"));
        }
        if (update == null || update.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "update required"));
        }

        schema.validateQuery(filter.keySet(), false);

        var compiled = compiler().compileUpdateMany(collection, filter, update);
        var results = executionService.executeBulkMutation(compiled);
        return ResponseEntity.ok(Map.of("data", results, "modified", results.size()));
    }

    @PostMapping("/{collection}/deleteMany")
    public ResponseEntity<Object> deleteMany(@PathVariable String collection,
                                              @RequestBody Map<String, Object> body) {
        var schema = resolveCollection(collection);
        @SuppressWarnings("unchecked")
        var filter = (Map<String, Object>) body.get("filter");
        if (filter == null || filter.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filter required"));
        }

        schema.validateQuery(filter.keySet(), false);

        var compiled = compiler().compileDeleteMany(collection, filter);
        var results = executionService.executeBulkMutation(compiled);
        return ResponseEntity.ok(Map.of("data", results, "deleted", results.size()));
    }

    @PostMapping("/{collection}/deleteOne")
    public ResponseEntity<Object> deleteOne(@PathVariable String collection,
                                             @RequestBody Map<String, Object> body) {
        var schema = resolveCollection(collection);
        @SuppressWarnings("unchecked")
        var filter = (Map<String, Object>) body.get("filter");
        if (filter == null || filter.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filter required"));
        }

        schema.validateQuery(filter.keySet(), false);

        var compiled = compiler().compileDeleteOne(collection, filter);
        try {
            var result = executionService.executeMutation(compiled);
            return ResponseEntity.ok(Map.of("data", result));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No matching document"));
        }
    }

    @DeleteMapping("/{collection}/{id}")
    public ResponseEntity<Object> deleteById(@PathVariable String collection,
                                              @PathVariable String id) {
        resolveCollection(collection);
        var compiled = compiler().compileDeleteOne(collection, Map.of("id", id));
        try {
            var result = executionService.executeMutation(compiled);
            return ResponseEntity.ok(Map.of("data", result));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
        }
    }

    @PostMapping("/{collection}/search")
    public ResponseEntity<Object> search(@PathVariable String collection,
                                          @RequestBody Map<String, Object> body) {
        resolveCollection(collection);
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query required"));
        }
        int limit = toInt(body.get("limit"), 10);
        var compiled = compiler().compileSearch(collection, query, limit);
        var results = executionService.executeQuery(compiled);
        return ResponseEntity.ok(Map.of("data", results));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/{collection}/vectorSearch")
    public ResponseEntity<Object> vectorSearch(@PathVariable String collection,
                                                @RequestBody Map<String, Object> body) {
        resolveCollection(collection);
        var embedding = (List<? extends Number>) body.get("embedding");
        if (embedding == null || embedding.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "embedding required"));
        }
        int topK = toInt(body.get("topK"), 5);
        var compiled = compiler().compileVectorSearch(collection, embedding, topK);
        var results = executionService.executeQuery(compiled);
        return ResponseEntity.ok(Map.of("data", results));
    }

    @PostMapping("/{collection}/count")
    public ResponseEntity<Object> count(@PathVariable String collection,
                                         @RequestBody(required = false) Map<String, Object> body) {
        resolveCollection(collection);
        @SuppressWarnings("unchecked")
        var filter = body != null ? (Map<String, Object>) body.getOrDefault("filter", Map.of()) : Map.<String, Object>of();
        var compiled = compiler().compileCount(collection, filter);
        long count = executionService.executeCount(compiled);
        return ResponseEntity.ok(Map.of("data", Map.of("count", count)));
    }

    private CollectionSchema resolveCollection(String collection) {
        return schemaManager.getCollectionInfo().getCollection(collection)
                .orElseThrow(() -> new NoSuchElementException("Unknown collection: " + collection));
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
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
