package io.github.excalibase.schema;

/**
 * A project's database is one this engine build carries no plugin for (a one-database image). The request is
 * refused as unavailable with a fixed message; the image to use is logged, not sent to the client.
 */
public class TenantEngineNotServedException extends SchemaUnavailableException {

    public TenantEngineNotServedException(String projectId, Throwable cause) {
        super("The database engine of " + projectId + " is not served by this engine build", cause);
    }
}
