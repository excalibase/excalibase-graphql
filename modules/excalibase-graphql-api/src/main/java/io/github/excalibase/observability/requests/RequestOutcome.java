package io.github.excalibase.observability.requests;

/** How a request ended, as the request charts group it. */
public enum RequestOutcome {
    OK("ok"),
    CLIENT_ERROR("client_error"),
    SERVER_ERROR("server_error");

    private final String tag;

    RequestOutcome(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    /** GraphQL answers request, permission and data errors with 200 and an {@code errors} array. */
    public static RequestOutcome of(int status, boolean graphqlErrors) {
        if (status >= 500) {
            return SERVER_ERROR;
        }
        if (status >= 400 || graphqlErrors) {
            return CLIENT_ERROR;
        }
        return OK;
    }
}
