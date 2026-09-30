package io.github.excalibase;

import com.sun.net.httpserver.HttpServer;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** Permission documents (docs/features/permissions.md §8) for integration tests, and serving them. */
public final class PermissionDocs {

    public static final String OWNED = "{\"owner_id\":{\"_eq\":\"X-Excalibase-User-Id\"}}";

    private PermissionDocs() {
    }

    /** A whole document of {@code tableEntries}, with no functions. */
    public static String document(String projectId, String... tableEntries) {
        return document(projectId, 1, tableEntries);
    }

    /** As above at {@code version}: a document that changes must move to a new version. */
    public static String document(String projectId, long version, String... tableEntries) {
        return "{\"projectId\":\"" + projectId + "\",\"version\":" + version + ",\"tables\":["
                + String.join(",", tableEntries) + "],\"functions\":[],\"functionPermissions\":[]}";
    }

    /** One {@code tables} entry; {@code operations} is the JSON after the names, e.g. {@code "select":{...}}. */
    public static String entry(String table, String role, String... operations) {
        return "{\"table\":\"" + table + "\",\"role\":\"" + role + "\"," + String.join(",", operations) + "}";
    }

    public static String select(String filter, String columns) {
        return "\"select\":{\"filter\":" + filter + ",\"columns\":" + columns + "}";
    }

    public static String select(String filter, String columns, Integer limit, boolean aggregations) {
        return "\"select\":{\"filter\":" + filter + ",\"columns\":" + columns
                + (limit == null ? "" : ",\"limit\":" + limit) + ",\"allowAggregations\":" + aggregations + "}";
    }

    public static String selectAll() {
        return select("{}", "\"*\"");
    }

    public static String insert(String check, String columns, String presets) {
        return "\"insert\":{\"check\":" + check + ",\"columns\":" + columns + ",\"set\":" + presets + "}";
    }

    public static String update(String filter, String check, String columns, String presets) {
        return "\"update\":{\"filter\":" + filter + ",\"check\":" + check + ",\"columns\":" + columns
                + ",\"set\":" + presets + "}";
    }

    public static String delete(String filter) {
        return "\"delete\":{\"filter\":" + filter + "}";
    }

    /** Every operation on every column, no filter. */
    public static String[] everything() {
        return new String[] {select("{}", "\"*\"", null, true), insert("{}", "\"*\"", "{}"),
                update("{}", "{}", "\"*\"", "{}"), delete("{}")};
    }

    /** Serves {@code document} at {@code {base}/provision/{projectId}/permissions/} under {@code /api}. */
    public static void serve(HttpServer server, String projectId, Supplier<String> document) {
        server.createContext("/api/provision/" + projectId + "/permissions/", exchange -> {
            byte[] bytes = document.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
