package io.github.excalibase.config.ws;

/**
 * Parses the project segment from a project-scoped WebSocket upgrade path,
 * mirroring the HTTP routes: {@code /{projectId}/graphql} and
 * {@code /{projectId}/api/v1/realtime}. {@code projectId} is a single opaque
 * segment. Returns {@code null} for an unscoped or malformed path — there is no
 * legacy unscoped WS route.
 */
final class WsProjectPath {

    static final String GRAPHQL_SUFFIX = "/graphql";
    static final String REALTIME_SUFFIX = "/api/v1/realtime";

    private WsProjectPath() {}

    /** The {@code projectId} in {@code /{projectId}{suffix}}, or {@code null} if the path isn't that shape. */
    static String projectId(String uri, String suffix) {
        if (uri == null || !uri.endsWith(suffix)) return null;
        String head = uri.substring(0, uri.length() - suffix.length()); // "/{projectId}"
        if (head.length() < 2 || head.charAt(0) != '/') return null;
        String projectId = head.substring(1);
        return projectId.contains("/") ? null : projectId; // single segment only
    }

    /** The projectId for whichever WS suffix the path matches (GraphQL or realtime), else {@code null}. */
    static String projectId(String uri) {
        String pid = projectId(uri, GRAPHQL_SUFFIX);
        return pid != null ? pid : projectId(uri, REALTIME_SUFFIX);
    }
}
