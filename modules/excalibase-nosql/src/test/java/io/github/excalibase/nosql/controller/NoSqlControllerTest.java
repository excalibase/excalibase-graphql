package io.github.excalibase.nosql.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.nosql.model.CollectionInfo;
import io.github.excalibase.nosql.model.CollectionSchema;
import io.github.excalibase.nosql.model.FieldType;
import io.github.excalibase.nosql.model.IndexDef;
import io.github.excalibase.nosql.schema.CollectionSchemaManager;
import io.github.excalibase.nosql.schema.JsonSchemaValidator;
import io.github.excalibase.nosql.service.DocumentExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class NoSqlControllerTest {

    @Mock CollectionSchemaManager schemaManager;
    @Mock DocumentExecutionService executionService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchemaValidator jsonSchemaValidator = new JsonSchemaValidator(objectMapper);

    @BeforeEach
    void setUp() {
        NoSqlController controller = new NoSqlController(schemaManager, executionService, jsonSchemaValidator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private CollectionInfo collectionInfoWithUsers() {
        CollectionInfo info = new CollectionInfo();
        info.addCollection("users", new CollectionSchema(
                "users",
                Map.<String, FieldType>of(),
                List.<IndexDef>of(),
                Set.of("email"),
                null,
                null));
        return info;
    }

    // ─── GET /{collection} ────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /users returns documents wrapped in data field")
    void find_returnsDocuments() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeQuery(any())).thenReturn(List.of(Map.of("id", 1, "name", "Alice")));

        mockMvc.perform(get("/api/v1/nosql/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Alice"));
    }

    @Test
    @DisplayName("GET /users?count returns count under data.count")
    void find_countMode_returnsCount() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeCount(any())).thenReturn(42L);

        mockMvc.perform(get("/api/v1/nosql/users").param("count", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(42));
    }

    @Test
    @DisplayName("GET /users?stats returns stats block")
    void find_statsMode_returnsStats() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(schemaManager.getCollectionStats("users")).thenReturn(Map.of("rowCount", 100));

        mockMvc.perform(get("/api/v1/nosql/users").param("stats", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowCount").value(100));
    }

    @Test
    @DisplayName("GET /users?search=query runs FTS and returns results")
    void find_searchMode_runsFts() throws Exception {
        CollectionInfo info = new CollectionInfo();
        info.addCollection("users", new CollectionSchema(
                "users", Map.of(), List.of(), Set.of(), "name", null));
        when(schemaManager.getCollectionInfo()).thenReturn(info);
        when(executionService.executeQuery(any())).thenReturn(List.of(Map.of("id", 1)));

        mockMvc.perform(get("/api/v1/nosql/users").param("search", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1));
    }

    @Test
    @DisplayName("GET with limit/offset/sort parameters compiles and executes")
    void find_withPaginationAndSort() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeQuery(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/nosql/users")
                        .param("limit", "5").param("offset", "10").param("sort", "name.desc,email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET with X-Debug:true adds X-Query-Time header")
    void find_debugHeader_addsQueryTime() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeQuery(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/nosql/users").header("X-Debug", "true"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Query-Time"));
    }

    @Test
    @DisplayName("GET with X-Debug:true and unindexed filter emits X-Warning")
    void find_debugWithUnindexedFilter_addsWarning() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeQuery(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/nosql/users").header("X-Debug", "true").param("city", "NYC"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Warning"));
    }

    @Test
    @DisplayName("GET on unknown collection returns 404")
    void find_unknownCollection_returns404() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(new CollectionInfo());

        mockMvc.perform(get("/api/v1/nosql/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Unknown collection")));
    }

    @Test
    @DisplayName("GET with query param operator prefixes (eq/gt/in/...) are parsed into filter")
    void find_withOperatorPrefixFilters() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeQuery(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/nosql/users")
                        .param("email", "eq.a@b.com")
                        .param("age", "gt.18")
                        .param("status", "in.active,pending"))
                .andExpect(status().isOk());
    }

    // ─── GET /{collection}/{id} ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /users/1 returns the single document")
    void getById_returnsDocument() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeSingleQuery(any())).thenReturn(Optional.of(Map.of("id", 1, "name", "Alice")));

        mockMvc.perform(get("/api/v1/nosql/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Alice"));
    }

    @Test
    @DisplayName("GET /users/99 returns 404 when document not found")
    void getById_notFound_returns404() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeSingleQuery(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/nosql/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not found"));
    }

    // ─── POST /{collection} ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST single doc under 'doc' key returns 201 Created")
    void insert_singleDocUnderDocKey_returns201() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeMutation(any())).thenReturn(Map.of("id", 1));

        mockMvc.perform(post("/api/v1/nosql/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("doc", Map.of("name", "Alice")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @DisplayName("POST doc at top level (no 'doc' key) also accepted")
    void insert_docAtTopLevel_returns201() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeMutation(any())).thenReturn(Map.of("id", 2));

        mockMvc.perform(post("/api/v1/nosql/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Bob"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST with 'docs' array bulk-inserts and returns 201")
    void insert_docsArray_bulkInsert() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeBulkMutation(any()))
                .thenReturn(List.of(Map.of("id", 1), Map.of("id", 2)));

        mockMvc.perform(post("/api/v1/nosql/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("docs",
                                List.of(Map.of("name", "A"), Map.of("name", "B"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("POST with empty 'docs' array returns 400")
    void insert_emptyDocsArray_returns400() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());

        mockMvc.perform(post("/api/v1/nosql/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docs\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("docs array required"));
    }

    @Test
    @DisplayName("POST with 'docs' containing non-object returns 400")
    void insert_docsWithNonObject_returns400() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());

        mockMvc.perform(post("/api/v1/nosql/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docs\":[\"not-an-object\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST with non-object 'doc' returns 400")
    void insert_nonObjectDoc_returns400() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());

        mockMvc.perform(post("/api/v1/nosql/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doc\":\"not-an-object\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST ?vector=true with empty embedding returns 400")
    void insert_vectorWithNoEmbedding_returns400() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());

        mockMvc.perform(post("/api/v1/nosql/users").param("vector", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("embedding required"));
    }

    @Test
    @DisplayName("POST ?vector=true with embedding runs vector search")
    void insert_vectorSearch_returnsResults() throws Exception {
        CollectionInfo info = new CollectionInfo();
        info.addCollection("users", new CollectionSchema(
                "users", Map.of(), List.of(), Set.of(), null,
                new io.github.excalibase.nosql.model.VectorDef("emb", 3)));
        when(schemaManager.getCollectionInfo()).thenReturn(info);
        when(executionService.executeQuery(any())).thenReturn(List.of(Map.of("id", 1)));

        mockMvc.perform(post("/api/v1/nosql/users").param("vector", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"embedding\":[0.1,0.2,0.3],\"topK\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ─── PATCH /{collection} ─────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH with filter updates matching documents")
    void update_withFilter_returnsModifiedCount() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeBulkMutation(any()))
                .thenReturn(List.of(Map.of("id", 1), Map.of("id", 2)));

        mockMvc.perform(patch("/api/v1/nosql/users").param("email", "eq.a@b.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modified").value(2));
    }

    @Test
    @DisplayName("PATCH without filter returns 400")
    void update_withoutFilter_returns400() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());

        mockMvc.perform(patch("/api/v1/nosql/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("filter required in query params"));
    }

    // ─── DELETE /{collection} ────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE with filter deletes matching documents")
    void delete_withFilter_returnsDeletedCount() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeBulkMutation(any()))
                .thenReturn(List.of(Map.of("id", 1)));

        mockMvc.perform(delete("/api/v1/nosql/users").param("email", "eq.old@b.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
    }

    @Test
    @DisplayName("DELETE without filter returns 400")
    void delete_withoutFilter_returns400() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());

        mockMvc.perform(delete("/api/v1/nosql/users"))
                .andExpect(status().isBadRequest());
    }

    // ─── DELETE /{collection}/{id} ───────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /users/1 returns the deleted doc")
    void deleteById_success() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeMutation(any())).thenReturn(Map.of("id", 1));

        mockMvc.perform(delete("/api/v1/nosql/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @DisplayName("DELETE /users/99 returns 404 when the executor throws EmptyResultDataAccessException")
    void deleteById_notFound() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeMutation(any()))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException("no rows", 1));

        mockMvc.perform(delete("/api/v1/nosql/users/99"))
                .andExpect(status().isNotFound());
    }

    // ─── PUT /{collection}/{id}/embedding ────────────────────────────────────────

    @Test
    @DisplayName("PUT embedding with numeric array succeeds")
    void setEmbedding_validNumericArray_success() throws Exception {
        CollectionInfo info = new CollectionInfo();
        info.addCollection("users", new CollectionSchema(
                "users", Map.of(), List.of(), Set.of(), null,
                new io.github.excalibase.nosql.model.VectorDef("emb", 3)));
        when(schemaManager.getCollectionInfo()).thenReturn(info);
        when(executionService.executeMutation(any())).thenReturn(Map.of("id", 1));

        mockMvc.perform(put("/api/v1/nosql/users/1/embedding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"embedding\":[0.1,0.2,0.3]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT embedding with non-numeric value returns 400")
    void setEmbedding_nonNumeric_returns400() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());

        mockMvc.perform(put("/api/v1/nosql/users/1/embedding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"embedding\":[\"not-a-number\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT embedding with empty array returns 400")
    void setEmbedding_emptyArray_returns400() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());

        mockMvc.perform(put("/api/v1/nosql/users/1/embedding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"embedding\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT embedding on missing id returns 404")
    void setEmbedding_notFound_returns404() throws Exception {
        CollectionInfo info = new CollectionInfo();
        info.addCollection("users", new CollectionSchema(
                "users", Map.of(), List.of(), Set.of(), null,
                new io.github.excalibase.nosql.model.VectorDef("emb", 3)));
        when(schemaManager.getCollectionInfo()).thenReturn(info);
        when(executionService.executeMutation(any()))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException("no rows", 1));

        mockMvc.perform(put("/api/v1/nosql/users/99/embedding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"embedding\":[0.1,0.2]}"))
                .andExpect(status().isNotFound());
    }

    // ─── Schema endpoints ────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST / on root runs syncSchema and returns the result under data")
    void syncSchema_returnsResult() throws Exception {
        when(schemaManager.syncSchema(any())).thenReturn(Map.of("created", 3));

        mockMvc.perform(post("/api/v1/nosql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collections\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(3));
    }

    @Test
    @DisplayName("GET / returns map of collections with their indexes")
    void getSchema_returnsAllCollections() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());

        mockMvc.perform(get("/api/v1/nosql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.users").exists());
    }

    // ─── Cursor pagination response shape ──────────────────────────────────────

    @Test
    @DisplayName("GET ?paginate=cursor returns {data, cursor} with non-null cursor when page full")
    void find_cursorMode_emitsNextCursor() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        var page = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < 2; i++) {
            page.add(Map.of("id", "id-" + i, "createdAt", "2026-04-22T12:00:0" + i + "Z"));
        }
        when(executionService.executeQuery(any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/nosql/users")
                        .param("paginate", "cursor")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.cursor").isNotEmpty());
    }

    @Test
    @DisplayName("GET ?paginate=cursor returns null cursor when results < limit")
    void find_cursorMode_nullCursorAtEnd() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        when(executionService.executeQuery(any())).thenReturn(List.of(
                Map.of("id", "last-id", "createdAt", "2026-04-22T12:00:00Z")));

        mockMvc.perform(get("/api/v1/nosql/users")
                        .param("paginate", "cursor")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.cursor").doesNotExist());
    }

    // ─── JSON Schema validation on insert ──────────────────────────────────────

    @Test
    @DisplayName("POST with schema-violating doc returns 400 with issues[]")
    void insert_validationError_returns400WithIssues() throws Exception {
        when(schemaManager.getCollectionInfo()).thenReturn(collectionInfoWithUsers());
        jsonSchemaValidator.registerSchema("users", Map.of(
                "type", "object",
                "required", List.of("email"),
                "properties", Map.of("email", Map.of("type", "string"))));

        mockMvc.perform(post("/api/v1/nosql/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doc\":{\"age\":30}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation"))
                .andExpect(jsonPath("$.issues").isArray());
        jsonSchemaValidator.clear();
    }
}
