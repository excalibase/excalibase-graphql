package io.github.excalibase.admin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** The version history of a file source, through the admin API: list, one entry, and restore as an ordinary save. */
class AdminHistoryIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(DOCUMENT);
    private static final Path HISTORY = FILE.resolveSibling("permissions.history");
    private static final String METADATA = "/_admin/v1/metadata";
    private static final String HISTORY_PATH = "/_admin/v1/history";
    private static final String IF_MATCH = "If-Match";
    private static final String COMMENTED = "# a comment worth keeping\n" + DOCUMENT;

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        return AdminTestEngine.consoleOn(FILE, MANAGEMENT_PORT, ADMIN_PORT);
    }

    private String sourceVersion() {
        return admin.get(METADATA, HEADER, SECRET).jsonPath("$.sourceVersion");
    }

    private HttpAnswer save(String text, String version) {
        return admin.send("PUT", METADATA, text, HEADER, SECRET, "Content-Type", "text/plain", IF_MATCH,
                "\"" + version + "\"");
    }

    private HttpAnswer saveNow(String text) {
        return save(text, sourceVersion());
    }

    private JsonNode entries() {
        return admin.get(HISTORY_PATH, HEADER, SECRET).json().path("entries");
    }

    private HttpAnswer restore(long id, String version) {
        return admin.send("POST", HISTORY_PATH + "/" + id + "/restore", "", HEADER, SECRET, IF_MATCH,
                "\"" + version + "\"");
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("every save adds the text it replaced; the list says when, which version and how big, newest first")
    void listsEntries() {
        assertThat(saveNow(COMMENTED).status()).isEqualTo(200);
        assertThat(saveNow(DOCUMENT).status()).isEqualTo(200);

        HttpAnswer answer = admin.get(HISTORY_PATH, HEADER, SECRET);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<Boolean>jsonPath("$.available")).isTrue();
        JsonNode newest = answer.json().path("entries").get(0);
        assertThat(newest.path("hash").asText()).isEqualTo(sha(COMMENTED));
        assertThat(newest.path("size").asInt()).isEqualTo(COMMENTED.getBytes(StandardCharsets.UTF_8).length);
        assertThat(newest.path("at").asText()).matches("\\d{4}-\\d\\d-\\d\\dT.*Z");
        assertThat(newest.path("id").asLong()).isPositive();
        assertThat(newest.has("text")).as("a list does not carry the texts").isFalse();
        assertThat(answer.json().path("entries").get(1).path("hash").asText()).isEqualTo(sha(DOCUMENT));
    }

    @Test
    @DisplayName("one entry comes back with its full text, comments included")
    void getsOneEntry() {
        saveNow(COMMENTED);
        saveNow(DOCUMENT);
        long id = entries().get(0).path("id").asLong();

        HttpAnswer answer = admin.get(HISTORY_PATH + "/" + id, HEADER, SECRET);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<String>jsonPath("$.text")).isEqualTo(COMMENTED);
        assertThat(answer.<String>jsonPath("$.hash")).isEqualTo(sha(COMMENTED));
    }

    @Test
    @DisplayName("an entry that is not there is 404 with a short code")
    void unknownEntry() {
        HttpAnswer answer = admin.get(HISTORY_PATH + "/12345", HEADER, SECRET);

        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.<String>jsonPath("$.error")).isEqualTo("history_entry_not_found");
        assertThat(admin.get(HISTORY_PATH + "/not-a-number", HEADER, SECRET).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("restore saves the entry's text as a normal conditional save, and the text it replaces is added, so it can be undone")
    void restores() throws IOException {
        saveNow(COMMENTED);
        saveNow(DOCUMENT);
        long id = entries().get(0).path("id").asLong();

        HttpAnswer answer = restore(id, sourceVersion());

        assertThat(answer.status()).isEqualTo(200);
        assertThat(Files.readString(FILE)).isEqualTo(COMMENTED);
        assertThat(admin.get(METADATA, HEADER, SECRET).<String>jsonPath("$.document")).isEqualTo(COMMENTED);
        assertThat(entries().get(0).path("hash").asText()).isEqualTo(sha(DOCUMENT));
        long undo = entries().get(0).path("id").asLong();
        assertThat(restore(undo, sourceVersion()).status()).isEqualTo(200);
        assertThat(Files.readString(FILE)).isEqualTo(DOCUMENT);
    }

    @Test
    @DisplayName("a restore that started from a stale version is 409 and writes nothing")
    void restoreConflict() throws IOException {
        saveNow(COMMENTED);
        saveNow(DOCUMENT);
        long id = entries().get(0).path("id").asLong();
        String before = Files.readString(FILE);
        int count = entries().size();

        HttpAnswer answer = restore(id, "not-it");

        assertThat(answer.status()).isEqualTo(409);
        assertThat(answer.<String>jsonPath("$.error")).isEqualTo("version_conflict");
        assertThat(Files.readString(FILE)).isEqualTo(before);
        assertThat(entries()).hasSize(count);
    }

    @Test
    @DisplayName("a restore without If-Match is 428")
    void restoreNeedsIfMatch() {
        saveNow(COMMENTED);
        saveNow(DOCUMENT);
        long id = entries().get(0).path("id").asLong();

        assertThat(admin.send("POST", HISTORY_PATH + "/" + id + "/restore", "", HEADER, SECRET).status()).isEqualTo(428);
    }

    @Test
    @DisplayName("an entry that no longer validates is refused with the reason, and nothing is written")
    void restoreInvalid() throws IOException {
        String stale = DOCUMENT + "surprise: true\n";
        long id = System.currentTimeMillis() + 1_000_000;
        String line = "{\"id\":" + id + ",\"at\":\"2026-11-20T10:00:00Z\",\"hash\":\"" + sha(stale) + "\",\"text\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(stale) + "}\n";
        Files.writeString(HISTORY, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String before = Files.readString(FILE);

        HttpAnswer answer = restore(id, sourceVersion());

        assertThat(answer.status()).isEqualTo(422);
        assertThat(answer.json().path("errors")).isNotEmpty();
        assertThat(Files.readString(FILE)).isEqualTo(before);
    }

    @Test
    @DisplayName("every history call needs the admin secret")
    void authRequired() {
        assertThat(admin.get(HISTORY_PATH).status()).isEqualTo(401);
        assertThat(admin.get(HISTORY_PATH + "/1").status()).isEqualTo(401);
        assertThat(admin.send("POST", HISTORY_PATH + "/1/restore", "", IF_MATCH, "\"x\"").status()).isEqualTo(401);
        assertThat(admin.get(HISTORY_PATH, HEADER, "wrong-secret-0123456789-abcdefghijklmnop").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("GET metadata says the source keeps a history")
    void metadataSaysHistory() {
        assertThat(admin.get(METADATA, HEADER, SECRET).<Boolean>jsonPath("$.historyAvailable")).isTrue();
    }
}
