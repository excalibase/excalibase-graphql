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
     * Project id from a project-scoped URI, or {@code null} for a path of no project route shape. The project is the
     * single leading segment; what follows it decides the shape, never the path's ending, so the REST table named
     * {@code graphql} ({@code /{projectId}/api/v1/graphql}) is a REST path of its project like any other.
     */
    public static String fromUri(String uri) {
        String projectId = leadingSegment(uri);
        if (projectId == null) {
            return null;
        }
        String rest = uri.substring(1 + projectId.length());
        return rest.equals(GRAPHQL_SUFFIX) || rest.equals(REST_PREFIX) || rest.startsWith(REST_PREFIX + "/")
                ? projectId : null;
    }

    /** Whether the URI is a project's GraphQL route; {@code /{projectId}/api/v1/graphql} is REST, not this. */
    public static boolean isGraphql(String uri) {
        String projectId = leadingSegment(uri);
        return projectId != null && uri.substring(1 + projectId.length()).equals(GRAPHQL_SUFFIX);
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
}
