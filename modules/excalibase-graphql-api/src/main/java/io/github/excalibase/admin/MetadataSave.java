package io.github.excalibase.admin;

import io.github.excalibase.metadata.AdminMetadataView;
import io.github.excalibase.metadata.DocumentValidation;
import io.github.excalibase.metadata.SaveOutcome;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The checks and answers of {@code PUT /_admin/v1/metadata}, in the order that keeps a bad request from reaching the
 * source: the source must be writable (405), the caller must say which version it started from (428), the document
 * must load with the engine's own rules (422), and only then does the source write conditionally (409 on another
 * version, 502 when the write fails) and reload. An answer never carries a driver or store message.
 */
final class MetadataSave {

    static final String IF_MATCH = "If-Match";

    private MetadataSave() {
    }

    static HttpResponse<Map<String, Object>> respond(AdminMetadataView metadata, AdminConfig.ValidationSettings rules,
                                                     String ifMatch, byte[] document) {
        if (metadata.document().isEmpty()) {
            return failure(HttpStatus.NOT_FOUND, "no_metadata_source");
        }
        if (!metadata.writable()) {
            return failure(HttpStatus.METHOD_NOT_ALLOWED, "source_read_only");
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            return failure(HttpStatus.PRECONDITION_REQUIRED, "if_match_required");
        }
        DocumentValidation.Result checked = DocumentValidation.validate(document, rules.reserved(), rules.projectId(),
                rules.envJwtConfigured());
        if (!checked.valid()) {
            return HttpResponse.<Map<String, Object>>status(HttpStatus.UNPROCESSABLE_ENTITY).body(checked.toMap());
        }
        return answer(metadata.save(new String(document, StandardCharsets.UTF_8), version(ifMatch)));
    }

    /** An ETag as a client quotes it: {@code "abc"} or {@code W/"abc"} name the version {@code abc}. */
    static String version(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static HttpResponse<Map<String, Object>> answer(SaveOutcome outcome) {
        return switch (outcome) {
            case SaveOutcome.Saved saved -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("version", saved.version());
                body.put("sourceVersion", saved.sourceVersion());
                yield HttpResponse.ok(body);
            }
            case SaveOutcome.Conflict conflict -> HttpResponse.<Map<String, Object>>status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "version_conflict", "currentVersion", conflict.currentVersion()));
            case SaveOutcome.ReadOnly _ -> failure(HttpStatus.METHOD_NOT_ALLOWED, "source_read_only");
            case SaveOutcome.Invalid invalid -> HttpResponse.<Map<String, Object>>status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("valid", false, "errors", invalid.errors()));
            case SaveOutcome.Failed failed -> failure(HttpStatus.BAD_GATEWAY, failed.category());
            case SaveOutcome.NotApplied notApplied -> failure(HttpStatus.INTERNAL_SERVER_ERROR, notApplied.category());
        };
    }

    private static HttpResponse<Map<String, Object>> failure(HttpStatus status, String error) {
        return HttpResponse.<Map<String, Object>>status(status).body(Map.of("error", error));
    }
}
