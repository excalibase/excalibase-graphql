package io.github.excalibase.admin;

import io.github.excalibase.metadata.AdminMetadataView;
import io.github.excalibase.metadata.DocumentValidation;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.schema.SchemaInfo;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.core.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The admin API. It is mounted on the admin port only (the framework gives that port its own listener, as it does
 * the management port), and {@code AdminPortFilter} has already checked the secret by the time a request gets here.
 * It reads, except {@code PUT /metadata} and {@code POST /history/{id}/restore}, the calls that save: see
 * {@link MetadataSave}.
 */
@Controller(value = "/_admin/v1", port = "${excalibase.admin.port:10001}")
@Requires(property = "excalibase.console.enabled", pattern = AdminSettings.ON_PATTERN)
@Produces(MediaType.APPLICATION_JSON)
public class AdminController {

    private final AdminMetadataView metadata;
    private final GraphqlSchemaManager schemas;
    private final AdminConfig.ValidationSettings validation;

    public AdminController(AdminMetadataView metadata, GraphqlSchemaManager schemas,
                           AdminConfig.ValidationSettings validation) {
        this.metadata = metadata;
        this.schemas = schemas;
        this.validation = validation;
    }

    /** The served document, with the version this replica holds. */
    @Get("/metadata")
    public HttpResponse<Map<String, Object>> metadata() {
        Optional<Map<String, Object>> document = metadata.document();
        if (document.isEmpty()) {
            return HttpResponse.notFound(Map.<String, Object>of("error", "no_metadata_source"));
        }
        return HttpResponse.ok(document.get());
    }

    /** Saves the document the source holds, if the caller started from the version it still holds. */
    @Put("/metadata")
    @Consumes(MediaType.ALL)
    public HttpResponse<Map<String, Object>> save(@Header(MetadataSave.IF_MATCH) @Nullable String ifMatch,
                                                  @Body byte[] document) {
        return MetadataSave.respond(metadata, validation, ifMatch, document);
    }

    /** The versions a save replaced, newest first; {@code available} is false for a source that keeps none. */
    @Get("/history")
    public Map<String, Object> history() {
        return HistoryAnswers.list(metadata.history());
    }

    /** One earlier version with its whole text. */
    @Get("/history/{id}")
    public HttpResponse<Map<String, Object>> historyEntry(String id) {
        return HistoryAnswers.entry(metadata.history(), id);
    }

    /** Puts an earlier version back as a normal conditional save: same checks, same answers as {@code PUT /metadata}. */
    @Post("/history/{id}/restore")
    @Consumes(MediaType.ALL)
    public HttpResponse<Map<String, Object>> restore(String id, @Header(MetadataSave.IF_MATCH) @Nullable String ifMatch) {
        return MetadataSave.restore(metadata, validation, ifMatch, id);
    }

    /** Checks a posted document and answers what is wrong with it. Nothing is applied. */
    @Post("/validate")
    @Consumes(MediaType.ALL)
    public Map<String, Object> validate(@Body byte[] document) {
        return DocumentValidation.validate(document, validation.reserved(), validation.projectId(),
                validation.envJwtConfigured()).toMap();
    }

    /** The posted document as a tree, for a client that edits it: a YAML document is read here, never in the browser. */
    @Post("/parse")
    @Consumes(MediaType.ALL)
    public Map<String, Object> parse(@Body byte[] document) {
        return DocumentValidation.tree(document, validation.reserved(), validation.projectId(),
                validation.envJwtConfigured());
    }

    /** This replica only: the others are visible through the actuator and the metrics. */
    @Get("/replicas")
    public Map<String, Object> replicas() {
        return Map.of("replicas", List.of(metadata.replica()));
    }

    @Get("/schema")
    public HttpResponse<Map<String, Object>> schema() {
        GraphqlSchemaManager.EngineState state = schemas.getEngineState();
        if (state == null) {
            return HttpResponse.<Map<String, Object>>status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.<String, Object>of("error", "schema_unavailable"));
        }
        SchemaInfo reflected = state.compiler().schemaInfo();
        return HttpResponse.ok(schemaView(reflected, validation.reserved()));
    }

    static Map<String, Object> schemaView(SchemaInfo schema, ReservedSchemas reserved) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tables", tables(schema, reserved));
        view.put("functions", functions(schema, reserved));
        return view;
    }

    private static List<Map<String, Object>> tables(SchemaInfo schema, ReservedSchemas reserved) {
        List<Map<String, Object>> tables = new ArrayList<>();
        schema.getTableNames().stream().sorted().forEach(key -> {
            String schemaName = schemaOf(schema, key);
            if (!reserved.isReserved(schemaName)) {
                tables.add(table(schema, key, schemaName));
            }
        });
        return tables;
    }

    private static Map<String, Object> table(SchemaInfo schema, String key, String schemaName) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("schema", schemaName);
        table.put("name", key.startsWith(schemaName + ".") ? key.substring(schemaName.length() + 1) : key);
        table.put("kind", schema.isView(key) ? "view" : "table");
        List<String> primaryKeys = schema.getPrimaryKeys(key);
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String column : schema.getColumns(key)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", column);
            entry.put("type", schema.getColumnType(key, column));
            entry.put("primaryKey", primaryKeys.contains(column));
            columns.add(entry);
        }
        table.put("columns", columns);
        return table;
    }

    private static String schemaOf(SchemaInfo schema, String key) {
        String schemaName = schema.getTableSchema(key);
        if (schemaName != null) {
            return schemaName;
        }
        int dot = key.indexOf('.');
        return dot > 0 ? key.substring(0, dot) : "";
    }

    private static List<Map<String, Object>> functions(SchemaInfo schema, ReservedSchemas reserved) {
        return schema.getFunctions().values().stream()
                .filter(function -> !reserved.isReserved(function.schema()))
                .filter(function -> !reserved.isReservedObject(function.returnTable()))
                .filter(function -> function.args().stream().noneMatch(arg -> reserved.isReservedObject(arg.type())))
                .sorted(Comparator.comparing(SchemaInfo.FunctionInfo::qualifiedName))
                .map(AdminController::function)
                .toList();
    }

    private static Map<String, Object> function(SchemaInfo.FunctionInfo function) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("schema", function.schema());
        entry.put("name", function.name());
        entry.put("kind", function.kind().name().toLowerCase(Locale.ROOT));
        entry.put("returnsSet", function.returnsSet());
        entry.put("returnTable", function.returnTable());
        entry.put("args", function.args().stream().map(arg -> {
            Map<String, Object> declared = new LinkedHashMap<>();
            declared.put("name", arg.name());
            declared.put("type", arg.type());
            declared.put("mode", arg.mode());
            return declared;
        }).toList());
        return entry;
    }
}
