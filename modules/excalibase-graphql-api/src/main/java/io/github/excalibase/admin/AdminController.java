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

    private static final String KEY_SCHEMA = "schema";
    /** The key of a link's table, and the kind of one that is not a view. */
    private static final String KEY_TABLE = "table";
    private static final String KEY_COLUMNS = "columns";


    private final AdminMetadataView metadata;
    private final GraphqlSchemaManager schemas;
    private final AdminConfig.ValidationSettings validation;
    private final AdminSchemaCheck schemaCheck;

    public AdminController(AdminMetadataView metadata, GraphqlSchemaManager schemas,
                           AdminConfig.ValidationSettings validation) {
        this.metadata = metadata;
        this.schemas = schemas;
        this.validation = validation;
        this.schemaCheck = new AdminSchemaCheck(schemas);
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
        return MetadataSave.respond(metadata, validation, ifMatch, document, schemaCheck.current());
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
        return MetadataSave.restore(metadata, validation, ifMatch, id, schemaCheck.current());
    }

    /** Checks a posted document and answers what is wrong with it. Nothing is applied. */
    @Post("/validate")
    @Consumes(MediaType.ALL)
    public Map<String, Object> validate(@Body byte[] document) {
        return DocumentValidation.validate(document, validation.reserved(), validation.projectId(),
                validation.engineAuth(), System::getenv, schemaCheck.current()).toMap();
    }

    /** The posted document as a tree, for a client that edits it: a YAML document is read here, never in the browser. */
    @Post("/parse")
    @Consumes(MediaType.ALL)
    public Map<String, Object> parse(@Body byte[] document) {
        return DocumentValidation.tree(document, validation.reserved(), validation.projectId(),
                validation.engineAuth(), schemaCheck.current());
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
                tables.add(table(schema, key, schemaName, reserved));
            }
        });
        return tables;
    }

    private static Map<String, Object> table(SchemaInfo schema, String key, String schemaName, ReservedSchemas reserved) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put(KEY_SCHEMA, schemaName);
        table.put("name", key.startsWith(schemaName + ".") ? key.substring(schemaName.length() + 1) : key);
        table.put("kind", schema.isView(key) ? "view" : KEY_TABLE);
        List<String> primaryKeys = schema.getPrimaryKeys(key);
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String column : schema.getColumns(key)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", column);
            entry.put("type", schema.getColumnType(key, column));
            entry.put("primaryKey", primaryKeys.contains(column));
            columns.add(entry);
        }
        table.put(KEY_COLUMNS, columns);
        table.put("references", links(schema, key, reserved, true));
        table.put("referencedBy", links(schema, key, reserved, false));
        return table;
    }

    /**
     * The keys that tie this table to another: those it holds ({@code outgoing}) or those held by tables pointing at it.
     * A table in a reserved schema is never named, so a key cannot reveal one.
     */
    private static List<Map<String, Object>> links(SchemaInfo schema, String key, ReservedSchemas reserved, boolean outgoing) {
        List<Map<String, Object>> links = new ArrayList<>();
        if (outgoing) {
            schema.getAllForwardFks().forEach((field, fk) -> {
                if (key.equals(ownerOf(field)) && !reserved.isReserved(schemaOf(schema, refKey(fk)))) {
                    links.add(link(schema, refKey(fk), fk.fkColumns(), fk.refColumns()));
                }
            });
        } else {
            schema.getAllReverseFks().forEach((field, relation) -> {
                if (key.equals(ownerOf(field)) && !reserved.isReserved(schemaOf(schema, relation.childTable()))) {
                    links.add(link(schema, relation.childTable(), relation.fkColumns(), relation.refColumns()));
                }
            });
        }
        links.sort(Comparator.comparing((Map<String, Object> link) -> String.valueOf(link.get(KEY_SCHEMA)))
                .thenComparing(link -> String.valueOf(link.get(KEY_TABLE)))
                .thenComparing(link -> String.valueOf(link.get(KEY_COLUMNS))));
        return links;
    }

    /** {@code columns} are on the table that holds the key; {@code referencedColumns} are on the table it points at. */
    private static Map<String, Object> link(SchemaInfo schema, String otherKey, List<String> columns, List<String> referencedColumns) {
        String otherSchema = schemaOf(schema, otherKey);
        Map<String, Object> link = new LinkedHashMap<>();
        link.put(KEY_SCHEMA, otherSchema);
        link.put(KEY_TABLE, otherKey.startsWith(otherSchema + ".") ? otherKey.substring(otherSchema.length() + 1) : otherKey);
        link.put(KEY_COLUMNS, columns);
        link.put("referencedColumns", referencedColumns);
        return link;
    }

    /** A relationship key is {@code <table>.<field>}; the table is everything before the last dot. */
    private static String ownerOf(String relationshipKey) {
        return relationshipKey.substring(0, relationshipKey.lastIndexOf('.'));
    }

    private static String refKey(SchemaInfo.FkInfo fk) {
        return fk.refSchema() != null && !fk.refTable().contains(".") ? fk.refSchema() + "." + fk.refTable() : fk.refTable();
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
        entry.put(KEY_SCHEMA, function.schema());
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
