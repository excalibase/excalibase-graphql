package io.github.excalibase.admin;

import io.github.excalibase.metadata.DocumentHistory;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The answers of the history calls. An entry is named by its numeric id; anything else names no entry. */
final class HistoryAnswers {

    private HistoryAnswers() {
    }

    static Map<String, Object> list(Optional<DocumentHistory> history) {
        List<Map<String, Object>> entries = new ArrayList<>();
        history.ifPresent(kept -> kept.list().forEach(entry -> entries.add(summary(entry))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", history.isPresent());
        body.put("readable", history.map(DocumentHistory::readable).orElse(true));
        body.put("entries", entries);
        return body;
    }

    static HttpResponse<Map<String, Object>> entry(Optional<DocumentHistory> history, String id) {
        if (history.isEmpty()) {
            return notFound("history_unavailable");
        }
        Optional<DocumentHistory.Entry> found = find(history.get(), id);
        if (found.isEmpty()) {
            return notFound("history_entry_not_found");
        }
        Map<String, Object> body = summary(found.get());
        body.put("text", found.get().text());
        return HttpResponse.ok(body);
    }

    static Optional<DocumentHistory.Entry> find(DocumentHistory history, String id) {
        try {
            return history.get(Long.parseLong(id));
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> summary(DocumentHistory.Entry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", entry.id());
        body.put("at", entry.at().toString());
        body.put("hash", entry.hash());
        body.put("size", entry.size());
        return body;
    }

    private static HttpResponse<Map<String, Object>> notFound(String error) {
        return HttpResponse.<Map<String, Object>>status(HttpStatus.NOT_FOUND).body(Map.of("error", error));
    }
}
