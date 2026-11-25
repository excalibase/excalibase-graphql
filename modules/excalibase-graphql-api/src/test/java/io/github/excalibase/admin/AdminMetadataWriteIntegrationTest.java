package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code PUT /_admin/v1/metadata} on a file source: the secret, {@code If-Match}, validation, the save and the reload. */
class AdminMetadataWriteIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(DOCUMENT);
    private static final String METADATA = "/_admin/v1/metadata";
    private static final String IF_MATCH = "If-Match";
    private static final String TYPE = "Content-Type";
    private static final String TEXT = "text/plain";
    private static final String BIGGER = DOCUMENT + """
              - table: public.customers
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        return AdminTestEngine.consoleOn(FILE, MANAGEMENT_PORT, ADMIN_PORT);
    }

    private HttpAnswer current() {
        return admin.get(METADATA, HEADER, SECRET);
    }

    private HttpAnswer put(String body, String... headers) {
        String[] all = new String[headers.length + 4];
        all[0] = HEADER;
        all[1] = SECRET;
        all[2] = TYPE;
        all[3] = TEXT;
        System.arraycopy(headers, 0, all, 4, headers.length);
        return admin.send("PUT", METADATA, body, all);
    }

    private HttpAnswer putFromCurrent(String body) {
        return put(body, IF_MATCH, "\"" + current().<String>jsonPath("$.sourceVersion") + "\"");
    }

    private static String onDisk() throws IOException {
        return Files.readString(FILE);
    }

    @Test
    @DisplayName("GET metadata says a file in a writable folder is writable")
    void writable() {
        assertThat(current().<Boolean>jsonPath("$.writable")).isTrue();
    }

    @Test
    @DisplayName("a save with the version the editor started from is written, reloaded and answered with the new version")
    void saves() throws IOException {
        long before = current().<Integer>jsonPath("$.version");

        HttpAnswer saved = putFromCurrent(BIGGER);

        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.<Integer>jsonPath("$.version")).isEqualTo((int) before + 1);
        assertThat(onDisk()).isEqualTo(BIGGER);
        HttpAnswer served = current();
        assertThat(served.<String>jsonPath("$.document")).isEqualTo(BIGGER);
        assertThat(served.<String>jsonPath("$.sourceVersion")).isEqualTo(saved.<String>jsonPath("$.sourceVersion"));
        assertThat(putFromCurrent(DOCUMENT).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a save without If-Match is 428 and writes nothing")
    void missingIfMatch() throws IOException {
        String before = onDisk();

        HttpAnswer answer = put(BIGGER);

        assertThat(answer.status()).isEqualTo(428);
        assertThat(onDisk()).isEqualTo(before);
    }

    @Test
    @DisplayName("a stale If-Match is 409 with the current version, and writes nothing")
    void stale() throws IOException {
        String before = onDisk();
        String version = current().jsonPath("$.sourceVersion");

        HttpAnswer answer = put(BIGGER, IF_MATCH, "\"not-it\"");

        assertThat(answer.status()).isEqualTo(409);
        assertThat(answer.<String>jsonPath("$.currentVersion")).isEqualTo(version);
        assertThat(onDisk()).isEqualTo(before);
    }

    @Test
    @DisplayName("an invalid document is 422 with errors and is never written")
    void invalid() throws IOException {
        String before = onDisk();

        HttpAnswer answer = putFromCurrent(DOCUMENT + "surprise: true\n");

        assertThat(answer.status()).isEqualTo(422);
        assertThat(answer.json().path("errors")).isNotEmpty();
        assertThat(onDisk()).isEqualTo(before);
    }

    @Test
    @DisplayName("a document for another project, or in a reserved schema, is refused like a loaded one")
    void sameRulesAsALoad() throws IOException {
        String before = onDisk();

        assertThat(putFromCurrent(DOCUMENT.replace("proj-admin", "someone-else")).status()).isEqualTo(422);
        assertThat(putFromCurrent(DOCUMENT.replace("public.orders", "excalibase_meta.metadata_documents")).status())
                .isEqualTo(422);
        assertThat(onDisk()).isEqualTo(before);
    }

    @Test
    @DisplayName("a document with its own jwt section is refused while the engine has token settings of its own")
    void envJwtFlag() throws IOException {
        String before = onDisk();
        String withJwt = DOCUMENT.replace("tables:", """
                jwt:
                  issuer: https://tenant.example.com/
                  audience: https://api.demo
                  jwksUrl: https://tenant.example.com/.well-known/jwks.json
                  algorithms: [RS256]
                  claims:
                    role: https://demo/role
                    defaultRole: user
                    userId: sub
                tables:""");

        assertThat(putFromCurrent(withJwt).status()).isEqualTo(422);
        assertThat(onDisk()).isEqualTo(before);
    }

    @Test
    @DisplayName("the secret is required: no secret and a wrong one are 401 and write nothing")
    void secretRequired() throws IOException {
        String before = onDisk();
        String version = "\"" + current().<String>jsonPath("$.sourceVersion") + "\"";

        assertThat(admin.send("PUT", METADATA, BIGGER, TYPE, TEXT, IF_MATCH, version).status()).isEqualTo(401);
        assertThat(admin.send("PUT", METADATA, BIGGER, TYPE, TEXT, IF_MATCH, version, HEADER,
                "a-wrong-secret-0123456789-abcdefghijklmnop").status()).isEqualTo(401);
        assertThat(onDisk()).isEqualTo(before);
    }

    @Test
    @DisplayName("the public and management ports never serve the write")
    void notOnOtherPorts() throws IOException {
        String before = onDisk();
        String version = "\"" + current().<String>jsonPath("$.sourceVersion") + "\"";

        assertThat(http.send("PUT", METADATA, BIGGER, HEADER, SECRET, IF_MATCH, version, TYPE, TEXT).status())
                .isEqualTo(404);
        assertThat(AdminTestEngine.on(MANAGEMENT_PORT).send("PUT", METADATA, BIGGER, HEADER, SECRET, IF_MATCH,
                version, TYPE, TEXT).status()).isEqualTo(404);
        assertThat(onDisk()).isEqualTo(before);
    }
}
