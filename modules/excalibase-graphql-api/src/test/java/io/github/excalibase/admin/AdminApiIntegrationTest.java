package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.PROJECT;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console is on, so there are three listeners: public, management and admin. The admin routes live on the admin
 * port only, and only the admin port serves nothing else; the endpoints answer a caller holding the secret.
 */
class AdminApiIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(DOCUMENT);
    private static final String ADMIN_METADATA = "/_admin/v1/metadata";
    private static final String VALIDATE = "/_admin/v1/validate";
    private static final String TEXT = "text/plain";
    private static final String PING = "{ __typename }";

    private final EngineClient management = AdminTestEngine.on(MANAGEMENT_PORT);
    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        return AdminTestEngine.consoleOn(FILE, MANAGEMENT_PORT, ADMIN_PORT);
    }

    // --- port isolation, every direction ---

    @ParameterizedTest
    @ValueSource(strings = {"/_admin/v1/metadata", "/_admin/v1/replicas", "/_admin/v1/schema", "/_admin", "/_admin/",
            "/_admin/v1/nothing"})
    @DisplayName("admin paths are a 404 on the public port, secret or not")
    void adminPathsOnThePublicPort(String path) {
        assertThat(http.get(path).body()).doesNotContain("project_not_found").contains("\"status\":404");
        assertThat(http.get(path).status()).isEqualTo(404);
        assertThat(http.get(path, HEADER, SECRET).status()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_admin/v1/metadata", "/_admin/v1/replicas", "/_admin/v1/schema", "/_admin"})
    @DisplayName("admin paths are a 404 on the management port, secret or not")
    void adminPathsOnTheManagementPort(String path) {
        assertThat(management.get(path).status()).isEqualTo(404);
        assertThat(management.get(path, HEADER, SECRET).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the admin port serves the admin API to a caller with the secret")
    void adminPortServesTheApi() {
        assertThat(admin.get(ADMIN_METADATA, HEADER, SECRET).status()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/" + PROJECT + "/graphql", "/" + PROJECT + "/api/v1/orders", "/actuator/health",
            "/actuator", "/", "/something"})
    @DisplayName("public routes are not served on the admin port, secret or not")
    void publicRoutesOnTheAdminPort(String path) {
        assertThat(admin.get(path).status()).isEqualTo(404);
        assertThat(admin.get(path, HEADER, SECRET).status()).isEqualTo(404);
        assertThat(admin.graphql(path, PING, HEADER, SECRET).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("an encoded spelling of the admin prefix reaches nothing on any port")
    void encodedAdminPrefix() {
        String target = "/%5Fadmin/v1/metadata";

        assertThat(admin.raw("GET", target, HEADER, SECRET).status()).isEqualTo(404);
        assertThat(http.raw("GET", target, HEADER, SECRET).status()).isEqualTo(404);
        assertThat(management.raw("GET", target, HEADER, SECRET).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the public and management ports keep serving what they served")
    void otherPortsAreUnchanged() {
        assertThat(management.get("/actuator/health").status()).isIn(200, 503);
        assertThat(http.get("/actuator/health").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the admin API sends no CORS headers: only the console's own origin may use it")
    void noCorsOnTheAdminPort() {
        HttpAnswer answer = admin.get(ADMIN_METADATA, HEADER, SECRET, "Origin", "https://evil.example");

        assertThat(answer.header("Access-Control-Allow-Origin")).isNull();
    }

    // --- GET metadata ---

    @Test
    @DisplayName("GET metadata returns the document, version, source and that it is not writable yet")
    void metadata() {
        HttpAnswer answer = admin.get(ADMIN_METADATA, HEADER, SECRET);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<String>jsonPath("$.source")).isEqualTo("file");
        assertThat(answer.<String>jsonPath("$.document")).isEqualTo(DOCUMENT);
        assertThat(answer.<Integer>jsonPath("$.version")).isEqualTo(1);
        assertThat(answer.<String>jsonPath("$.sourceVersion")).isNotBlank();
        assertThat(answer.<Boolean>jsonPath("$.writable")).isFalse();
        assertThat(answer.json().path("history")).isEmpty();
        assertThat(answer.json().path("history").isArray()).isTrue();
    }

    // --- POST validate ---

    private HttpAnswer validate(String body, String contentType) {
        return admin.post(VALIDATE, body, HEADER, SECRET, "Content-Type", contentType);
    }

    @Test
    @DisplayName("POST validate accepts a good document and says so")
    void validateGood() {
        HttpAnswer answer = validate(DOCUMENT, TEXT);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<Boolean>jsonPath("$.valid")).isTrue();
        assertThat(answer.<Integer>jsonPath("$.tables")).isEqualTo(1);
    }

    @Test
    @DisplayName("POST validate lists the errors of a bad document")
    void validateBad() {
        HttpAnswer answer = validate("format: 1\nprojectId: [unclosed\n", "application/yaml");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<Boolean>jsonPath("$.valid")).isFalse();
        assertThat(answer.json().path("errors")).isNotEmpty();
    }

    @Test
    @DisplayName("POST validate refuses a document for another project, as the engine would")
    void validateOtherProject() {
        HttpAnswer answer = validate(DOCUMENT.replace(PROJECT, "someone-else"), TEXT);

        assertThat(answer.<Boolean>jsonPath("$.valid")).isFalse();
    }

    @Test
    @DisplayName("POST validate refuses a jwt: section while the engine has token settings of its own")
    void validateRefusesJwtSectionBesideEnvJwt() {
        String withJwt = DOCUMENT.replace("tables:", """
                jwt:
                  issuer: https://idp.example.com/
                  audience: a
                  jwksUrl: https://idp.example.com/jwks.json
                  algorithms: [RS256]
                  claims:
                    role: role
                    defaultRole: user
                    userId: sub
                tables:""");

        HttpAnswer answer = validate(withJwt, TEXT);

        assertThat(answer.<Boolean>jsonPath("$.valid")).isFalse();
        assertThat(answer.json().path("errors")).isNotEmpty();
    }

    @Test
    @DisplayName("validate never changes the served document, the version or the file")
    void validateNeverApplies() throws IOException {
        String better = DOCUMENT + """
                  - table: public.secrets
                    role: admin
                    select:
                      filter: {}
                      columns: ["id"]
                """;
        HttpAnswer before = admin.get(ADMIN_METADATA, HEADER, SECRET);

        HttpAnswer validated = validate(better, TEXT);
        validate("garbage: [", TEXT);

        assertThat(validated.<Boolean>jsonPath("$.valid")).isTrue();
        HttpAnswer after = admin.get(ADMIN_METADATA, HEADER, SECRET);
        assertThat(after.<String>jsonPath("$.document")).isEqualTo(before.<String>jsonPath("$.document"));
        assertThat(after.<Integer>jsonPath("$.version")).isEqualTo(before.<Integer>jsonPath("$.version"));
        assertThat(after.<String>jsonPath("$.sourceVersion")).isEqualTo(before.<String>jsonPath("$.sourceVersion"));
        assertThat(Files.readString(FILE)).isEqualTo(DOCUMENT);
    }

    // --- GET replicas ---

    @Test
    @DisplayName("GET replicas reports this replica's version and state")
    void replicas() {
        HttpAnswer answer = admin.get("/_admin/v1/replicas", HEADER, SECRET);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.json().path("replicas")).hasSize(1);
        assertThat(answer.<Boolean>jsonPath("$.replicas[0].self")).isTrue();
        assertThat(answer.<Integer>jsonPath("$.replicas[0].version")).isEqualTo(1);
        assertThat(answer.<Boolean>jsonPath("$.replicas[0].ready")).isTrue();
        assertThat(answer.<String>jsonPath("$.replicas[0].source")).isEqualTo("file");
    }

    // --- the writes of a later change are not here ---

    @Test
    @DisplayName("there is no write endpoint yet: PUT metadata and POST try are not routes")
    void noWritesYet() {
        assertThat(admin.send("PUT", ADMIN_METADATA, DOCUMENT, HEADER, SECRET).status()).isIn(404, 405);
        assertThat(admin.post("/_admin/v1/try", "{}", HEADER, SECRET, "Content-Type", "application/json").status())
                .isIn(404, 405);
    }
}
