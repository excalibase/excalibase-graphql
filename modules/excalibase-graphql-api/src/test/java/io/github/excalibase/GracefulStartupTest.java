package io.github.excalibase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.HttpAnswer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The application starts gracefully when the database is unreachable (bad URL): GraphQL requests answer with
 * meaningful errors instead of 500s, and introspection returns a valid (empty) schema.
 */
class GracefulStartupTest extends EngineIntegrationTest {

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.security.jwt-enabled", "false",
                "app.security.insecure-dev-mode", "true",
                "app.database-type", "postgres",
                "app.nats.enabled", "false");
    }

    private HttpAnswer graphql(String query) {
        return http.postJson("/test-proj/graphql", Map.of("query", query));
    }

    // Authentication is the JWT filter alone: no framework security module with a built-in user may be present,
    // so there is no second way in.
    @Test
    @DisplayName("no default user is created: there is no security module besides the JWT filter")
    void noDefaultUserIsCreated() {
        assertThatThrownBy(() -> Class.forName("io.micronaut.security.authentication.Authenticator"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.springframework.security.core.userdetails.UserDetailsService"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("the application starts without a database")
    void appStartsWithoutDatabase() {
        assertThat(server.isRunning()).isTrue();
    }

    @Test
    @DisplayName("a GraphQL request with no database answers with errors, not a server crash")
    void graphqlEndpoint_returnsError_notServerCrash() {
        HttpAnswer answer = graphql("{ users { id } }");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.json().has("errors")).isTrue();
    }

    @Test
    @DisplayName("introspection returns a valid schema when there is no database")
    void introspection_returnsValidSchema_whenNoDatabase() {
        HttpAnswer answer = graphql("{ __schema { queryType { name } } }");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.json().at("/data/__schema/queryType/name").asText()).isEqualTo("Query");
    }

    @Test
    @DisplayName("there is no mutation type when there are no tables")
    void introspection_noMutationType_whenNoTables() {
        HttpAnswer answer = graphql("{ __schema { mutationType } }");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.json().at("/data/__schema/mutationType").isMissingNode()
                || answer.json().at("/data/__schema/mutationType").isNull()).isTrue();
    }

    @Test
    @DisplayName("an empty query is a bad request")
    void missingQuery_returnsBadRequest() {
        assertThat(graphql("").status()).isEqualTo(400);
    }
}
