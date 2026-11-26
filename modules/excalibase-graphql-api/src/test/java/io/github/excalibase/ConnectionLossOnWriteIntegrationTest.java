package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.is;

/**
 * A write whose connection the database kills is told apart by when it happened: inside the statement nothing was
 * kept and a retry is safe (503 project_unavailable); at COMMIT the write may have been applied, so the client is
 * told the outcome is unknown (commit_unknown) and must not retry blindly. On REST and on GraphQL, whether or not
 * the caller is signed in. (EXC-812)
 */
class ConnectionLossOnWriteIntegrationTest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-connection-loss.sql");

    private static final String REST = "/test-proj/api/v1";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        return properties;
    }


    @Test
    void rest_connectionKilledAtCommit_isCommitUnknown() {
        http.post(REST + "/at_commit", "{\"note\":\"x\"}", "Content-Type", "application/json")
                .expect(status().is(500))
                .expect(jsonPath("$.code", is("commit_unknown")));
    }

    @Test
    void rest_connectionKilledInsideTheStatement_isAnOutageAClientMayRetry() {
        http.post(REST + "/in_statement", "{\"note\":\"x\"}", "Content-Type", "application/json")
                .expect(status().isServiceUnavailable())
                .expect(jsonPath("$.code", is("project_unavailable")));
    }

    @Test
    void graphql_connectionKilledAtCommit_isCommitUnknown_evenWithNoCaller() throws Exception {
        http.post("/test-proj/graphql", mapper.writeValueAsString(Map.of("query",
                        "mutation { createPublicAtCommit(input: { note: \"x\" }) { id } }")),
                        "Content-Type", "application/json")
                .expect(status().is(500))
                .expect(jsonPath("$.errors[0].extensions.code", is("commit_unknown")));
    }

    @Test
    void graphql_connectionKilledInsideTheStatement_isAnOutageAClientMayRetry() throws Exception {
        http.post("/test-proj/graphql", mapper.writeValueAsString(Map.of("query",
                        "mutation { createPublicInStatement(input: { note: \"x\" }) { id } }")),
                        "Content-Type", "application/json")
                .expect(status().isServiceUnavailable())
                .expect(jsonPath("$.errors[0].extensions.code", is("project_unavailable")));
    }

    @Test
    void rest_deferredForeignKeyFailingAtCommit_isAForeignKeyViolation_notAnInternalError() {
        http.post(REST + "/deferred_child", "{\"id\":1,\"parent_id\":999}", "Content-Type", "application/json")
                .expect(status().isBadRequest())
                .expect(jsonPath("$.code", is("foreign_key_violation")));
    }

    /** A serialization failure at COMMIT rolled the write back, so it is run again, not reported as unknown. (EXC-817) */
    @Test
    void rest_serializationFailureAtCommit_isRetried() {
        http.post(REST + "/commit_conflict", "{\"note\":\"x\"}", "Content-Type", "application/json")
                .expect(status().isCreated());
        http.get(REST + "/commit_conflict")
                .expect(status().isOk())
                .expect(jsonPath("$.data.length()", is(1)));
    }
}
