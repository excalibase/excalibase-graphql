package io.github.excalibase.security;

/** The project named by a request does not exist in this deployment. */
public class UnknownProjectException extends RuntimeException {

    public UnknownProjectException(String projectId) {
        super("Unknown project: " + projectId);
    }
}
