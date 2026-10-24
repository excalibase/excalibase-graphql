package io.github.excalibase.observability.requests;

import io.github.excalibase.security.ProjectPath;

import java.util.Optional;

/** Which project API a request used: {@code /{projectId}/graphql} or {@code /{projectId}/api/v1/...}. */
public enum RequestFamily {
    GRAPHQL("graphql"),
    REST("rest");

    private static final String GRAPHQL_SUFFIX = "/graphql";

    private final String tag;

    RequestFamily(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    /** Empty for every route that is not project-scoped (health, actuator, legacy unscoped paths). */
    public static Optional<RequestFamily> of(String uri) {
        if (ProjectPath.fromUri(uri) == null) {
            return Optional.empty();
        }
        return Optional.of(uri.endsWith(GRAPHQL_SUFFIX) ? GRAPHQL : REST);
    }
}
