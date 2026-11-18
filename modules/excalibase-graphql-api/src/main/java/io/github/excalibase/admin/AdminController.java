package io.github.excalibase.admin;

import io.github.excalibase.metadata.AdminMetadataView;
import io.github.excalibase.metadata.DocumentValidation;
import io.github.excalibase.schema.GraphqlSchemaManager;
import io.github.excalibase.schema.SchemaInfo;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read-only admin API. It is mounted on the admin port only (the framework gives that port its own listener,
 * as it does the management port), and {@code AdminPortFilter} has already checked the secret by the time a request
 * gets here. It reads: nothing a caller posts is applied.
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

    /** Checks a posted document and answers what is wrong with it. Nothing is applied. */
    @Post("/validate")
    @Consumes(MediaType.ALL)
    public Map<String, Object> validate(@Body byte[] document) {
        return DocumentValidation.validate(document, validation.reserved(), validation.projectId(),
                validation.envJwtConfigured()).toMap();
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
        return HttpResponse.ok(AdminSchemaView.of(reflected, validation.reserved()));
    }
}
