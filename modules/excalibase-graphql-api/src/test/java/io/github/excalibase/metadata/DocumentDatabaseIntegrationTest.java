package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A standalone engine started with {@code APP_DATABASE_TYPE=mongodb}: GraphQL answers from a real MongoDB, through the
 * engine's own permission file, and each role reads only what that file allows.
 */
class DocumentDatabaseIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-mongo";
    private static final String DATABASE = "shopdb";
    private static final String ROLE_CLAIM = "https://demo/role";
    private static final String QUERY = "{ orders { id owner total } }";

    private static final GenericContainer<?> MONGO = new GenericContainer<>(DockerImageName.parse("mongo:7"))
            .withExposedPorts(27017)
            .waitingFor(Wait.forLogMessage(".*[Ww]aiting for connections.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JwksStub IDP = new JwksStub();
    private static final Path DIRECTORY = newDirectory();

    private static Path newDirectory() {
        try {
            return Files.createTempDirectory("mongo-document-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path write(String name, String text) {
        try {
            Path file = DIRECTORY.resolve(name);
            Files.writeString(file, text);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        IDP.close();
        MONGO.stop();
    }

    private static String permissions() {
        return """
                format: 1
                projectId: %s
                jwt:
                  issuer: "%s"
                  jwksUrl: %s
                  audience: %s
                  claims:
                    role: %s
                tables:
                  - table: %s.orders
                    role: user
                    select:
                      filter: {"owner": {"_eq": "X-Excalibase-User-Id"}}
                      columns: ["id", "owner", "total"]
                  - table: %s.orders
                    role: reader
                    select:
                      filter: {}
                      columns: "*"
                """.formatted(PROJECT, IDP.issuer(), IDP.jwksUrl(), JwksStub.AUDIENCE, ROLE_CLAIM, DATABASE, DATABASE);
    }

    @Override
    public Map<String, String> getProperties() {
        MONGO.start();
        try (MongoClient client = MongoClients.create(uri())) {
            var orders = client.getDatabase(DATABASE).getCollection("orders");
            orders.drop();
            orders.insertMany(List.of(
                    new Document("owner", "alice").append("total", 10).append("secret", "a1"),
                    new Document("owner", "alice").append("total", 20).append("secret", "a2"),
                    new Document("owner", "bob").append("total", 30).append("secret", "b1")));
        }
        Path collections = write("collections.yaml", """
                collections:
                  - name: orders
                    fields:
                      owner: string
                      total: int
                      secret: string
                """);
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("app.database-type", "mongodb");
        properties.put("app.project-id", PROJECT);
        properties.put("app.mongodb.uri", uri());
        properties.put("app.mongodb.database", DATABASE);
        properties.put("app.mongodb.collections-file", collections.toString());
        properties.put("app.security.jwt-enabled", "true");
        properties.put("metadata.source", "file");
        properties.put("metadata.file.path", write("permissions.yaml", permissions()).toString());
        properties.put("metadata.file.poll-interval-ms", "50");
        return properties;
    }

    private static String uri() {
        return "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017) + "/?directConnection=true";
    }

    private String token(String subject, String role) {
        return IDP.rs256(IDP.claims().subject(subject).audience(JwksStub.AUDIENCE).claim(ROLE_CLAIM, role).build());
    }

    private HttpAnswer graphql(String token, String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", query)),
                "Authorization", "Bearer " + token, "Content-Type", "application/json");
    }

    @Test
    void aRoleReadsOnlyTheDocumentsItsPermissionFilterAllows() throws Exception {
        graphql(token("alice", "user"), QUERY).expect(jsonPath("$.data.orders", hasSize(2)))
                .expect(jsonPath("$.data.orders[*].owner", containsInAnyOrder("alice", "alice")));
        graphql(token("bob", "user"), QUERY).expect(jsonPath("$.data.orders", hasSize(1)))
                .expect(jsonPath("$.data.orders[0].total", is(30)));
    }

    @Test
    void aRoleWithAnEmptyFilterReadsEveryDocument() throws Exception {
        graphql(token("carol", "reader"), "{ orders { id secret } }").expect(jsonPath("$.data.orders", hasSize(3)));
    }

    @Test
    void theColumnListRemovesTheFieldsItLeavesOut() throws Exception {
        HttpAnswer answer = graphql(token("alice", "user"), "{ orders { id secret } }");

        assertThat(answer.body()).contains("errors").doesNotContain("a1");
    }

    @Test
    void aRoleTheFileDoesNotNameReadsNothing() throws Exception {
        graphql(token("alice", "stranger"), QUERY).expect(jsonPath("$.data.orders", empty()));
    }

    @Test
    void introspectionAnswersFromTheDeclaredCollections() throws Exception {
        graphql(token("alice", "user"), "{ __schema { queryType { name } } }")
                .expect(jsonPath("$.data.__schema.queryType.name", is("Query")));
        graphql(token("alice", "user"), "{ __type(name: \"Orders\") { fields { name } } }")
                .expect(jsonPath("$.data.__type", notNullValue()));
    }

    @Test
    void aRequestWithoutATokenReadsNothing() throws Exception {
        HttpAnswer answer = http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", QUERY)),
                "Content-Type", "application/json");

        assertThat(answer.body()).doesNotContain("alice").doesNotContain("bob");
    }

    @Test
    void restIsRefusedAsNotServedYetAndReturnsNoDocuments() {
        HttpAnswer answer = http.get("/" + PROJECT + "/api/v1/orders", "Authorization", "Bearer " + token("carol", "reader"));

        assertThat(answer.status()).isEqualTo(501);
        assertThat(answer.body()).contains("surface_not_served").doesNotContain("alice");
    }
}
