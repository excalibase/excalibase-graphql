package io.github.excalibase.admin;

import io.github.excalibase.metadata.AdminMetadataView;
import io.github.excalibase.metadata.DocumentHistory;
import io.github.excalibase.metadata.DocumentValidation;
import io.github.excalibase.metadata.SaveOutcome;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Puts an earlier version back: an ordinary save of its text, so it is validated again, conditional on the version
     * the caller started from, and adds the text it replaces to the history, which makes a restore undoable too.
     */
    static HttpResponse<Map<String, Object>> restore(AdminMetadataView metadata, AdminConfig.ValidationSettings rules,
                                                     String ifMatch, String id) {
        Optional<DocumentHistory> history = metadata.history();
        if (history.isEmpty()) {
            return failure(HttpStatus.NOT_FOUND, "history_unavailable");
        }
        Optional<DocumentHistory.Entry> entry = HistoryAnswers.find(history.get(), id);
        if (entry.isEmpty()) {
            return failure(HttpStatus.NOT_FOUND, "history_entry_not_found");
        }
        return respond(metadata, rules, ifMatch, entry.get().text().getBytes(StandardCharsets.UTF_8));
    }

    /** An ETag as a client quotes it: {@code "abc"} names the version {@code abc}. A weak tag names none (RFC 9110). */
    static String version(String ifMatch) {
        String value = ifMatch.strip();
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    static HttpResponse<Map<String, Object>> answer(SaveOutcome outcome) {
        return switch (outcome) {
            case SaveOutcome.Saved(long version, String sourceVersion, Boolean historyRecorded) -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("version", version);
                body.put("sourceVersion", sourceVersion);
                if (historyRecorded != null) {
                    body.put("historyRecorded", historyRecorded);
                }
                yield HttpResponse.ok(body);
            }
            case SaveOutcome.Conflict(String currentVersion) -> HttpResponse.<Map<String, Object>>status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "version_conflict", "currentVersion", currentVersion));
            case SaveOutcome.ReadOnly _ -> failure(HttpStatus.METHOD_NOT_ALLOWED, "source_read_only");
            case SaveOutcome.Invalid(List<String> errors) -> HttpResponse.<Map<String, Object>>status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("valid", false, "errors", errors));
            case SaveOutcome.Failed(String category) -> failure(HttpStatus.BAD_GATEWAY, category);
            case SaveOutcome.NotApplied(String category) -> failure(HttpStatus.INTERNAL_SERVER_ERROR, category);
        };
    }

    private static HttpResponse<Map<String, Object>> failure(HttpStatus status, String error) {
        return HttpResponse.<Map<String, Object>>status(status).body(Map.of("error", error));
    }
}
