package io.github.excalibase.security;

/**
 * The one routing rule every project-scoped surface shares: the project is the
 * single leading path segment of {@code /{projectId}/graphql} or
 * {@code /{projectId}/api/v1/...} (REST and the realtime WebSocket upgrade).
 * Auth, RLS and CORS all read the project from here, so the resource — not the
 * caller — decides which project a request belongs to.
 */
public final class ProjectPath {

    private static final String GRAPHQL_SUFFIX = "/graphql";
    private static final String REST_PREFIX = "/api/v1";

    private ProjectPath() {}

    /**
     * Project id from a project-scoped URI, or {@code null} for a path of no project route shape. The projectId is a
     * single opaque segment.
     */
    public static String fromUri(String uri) {
        if (uri == null) {
            return null;
        }
        if (uri.endsWith(GRAPHQL_SUFFIX)) {
            return segment(uri.substring(0, uri.length() - GRAPHQL_SUFFIX.length()));
        }
        int api = uri.indexOf(REST_PREFIX);
        if (api > 0) {
            return segment(uri.substring(0, api));
        }
        return null;
    }

    /**
     * The first path segment, which is the project on every surface; {@code null} for {@code /} or an empty path.
     * It is not checked to be a project: the known-projects check does that.
     */
    public static String leadingSegment(String uri) {
        if (uri == null || !uri.startsWith("/")) {
            return null;
        }
        int end = uri.indexOf('/', 1);
        String segment = end < 0 ? uri.substring(1) : uri.substring(1, end);
        return segment.isEmpty() ? null : segment;
    }

    /** A single non-empty path segment ({@code /proj-abc} → {@code proj-abc}); null otherwise. */
    private static String segment(String prefix) {
        if (!prefix.startsWith("/")) {
            return null;
        }
        String projectId = prefix.substring(1);
        return (projectId.isEmpty() || projectId.contains("/")) ? null : projectId;
    }
}
