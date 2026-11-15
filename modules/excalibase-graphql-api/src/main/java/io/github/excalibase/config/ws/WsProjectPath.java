package io.github.excalibase.config.ws;

import io.github.excalibase.security.KnownProjects;

/**
 * Parses the project segment from a project-scoped WebSocket upgrade path,
 * mirroring the HTTP routes: {@code /{projectId}/graphql} and
 * {@code /{projectId}/api/v1/realtime}. {@code projectId} is a single opaque
 * segment of the shape of a project id. Returns {@code null} for an unscoped or malformed path — there is no
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
        // One segment of the shape every project id has: an encoded slash or dot segment is not one.
        return KnownProjects.PROJECT_ID.matcher(projectId).matches() ? projectId : null;
    }

    /** True when the path is, or with a trailing slash would be, one of the two WebSocket routes of some project. */
    static boolean isWebSocketRoute(String uri) {
        if (uri == null) return false;
        String path = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        return path.endsWith(GRAPHQL_SUFFIX) || path.endsWith(REALTIME_SUFFIX);
    }

    /** The projectId for whichever WS suffix the path matches (GraphQL or realtime), else {@code null}. */
    static String projectId(String uri) {
        String pid = projectId(uri, GRAPHQL_SUFFIX);
        return pid != null ? pid : projectId(uri, REALTIME_SUFFIX);
    }
}
