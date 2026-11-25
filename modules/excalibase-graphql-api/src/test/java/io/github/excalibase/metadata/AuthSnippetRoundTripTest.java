package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.ReservedSchemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The console writes the engine settings for a document's {@code jwt:} section, and the engine takes the section beside
 * its own settings only when they say exactly the same. The cases are shared with the console's own test (one file of
 * sections, the report the engine gives for each, and the lines the console writes), so each step is checked on its
 * side: section to report here, report to lines there, lines to an identical comparison here.
 */
class AuthSnippetRoundTripTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CASES = Path.of("console-ui/src/lib/authSnippetCases.json");
    private static final String SECRET = "a-shared-secret-of-at-least-32-bytes!!";
    private static final String PLACEHOLDER = "<a secret of 32 bytes or more>";
    private static final String HEADER = "format: 1\nprojectId: demo\n";
    private static final String RULES = """
            tables:
              - table: public.orders
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;
    private static final List<String> SHOWN = List.of("source", "issuer", "jwksUrl", "algorithms", "audience", "clockSkew",
            "claims", "secret");

    private static JsonNode cases() throws IOException {
        return JSON.readTree(Files.readString(CASES));
    }

    private static String document(String section, Path secretFile) {
        String filled = section.replace("@SECRET_FILE@", secretFile.toString());
        StringBuilder jwt = new StringBuilder("jwt:\n");
        filled.lines().forEach(line -> jwt.append("  ").append(line).append('\n'));
        return HEADER + jwt + RULES;
    }

    private static Map<String, String> environment(JsonNode lines) {
        Map<String, String> env = new LinkedHashMap<>();
        for (JsonNode line : lines) {
            String text = line.asText();
            int equals = text.indexOf('=');
            String value = text.substring(equals + 1);
            if (value.startsWith("\"")) {
                value = JSON.convertValue(parse(value), String.class);
            }
            env.put(text.substring(0, equals), PLACEHOLDER.equals(value) ? SECRET : value);
        }
        return env;
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A number is the same number whether it came as an int or a long. */
    private static boolean same(JsonNode expected, JsonNode actual) {
        return expected.isNumber() && actual.isNumber() ? expected.decimalValue().compareTo(actual.decimalValue()) == 0
                : expected.equals(actual);
    }

    @Test
    @DisplayName("the report the engine gives for a section is the one the console writes its lines from")
    void sectionToReport(@TempDir Path dir) throws IOException {
        Path secretFile = Files.writeString(dir.resolve("secret"), SECRET);
        for (JsonNode one : cases()) {
            String text = document(one.get("section").asText(), secretFile);
            PermissionSet permissions = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo").parse(text, 1);
            MetadataSource.Snapshot served = new MetadataSource.Snapshot(permissions, 1, "v1", Instant.now(), null, text);

            JsonNode report = JSON.valueToTree(AuthReport.of(EngineAuth.NONE, null, served, name -> null));

            List<String> wrong = new ArrayList<>();
            for (String field : SHOWN) {
                JsonNode expected = one.get("report").path(field);
                JsonNode actual = report.path(field);
                if (!(expected.isMissingNode() ? actual.isMissingNode() || actual.isNull() : same(expected, actual))) {
                    wrong.add(field);
                }
            }
            assertThat(wrong).as(one.get("name").asText()).isEmpty();
        }
    }

    @Test
    @DisplayName("the lines the console writes make engine settings the section is identical to, for each case")
    void linesToIdenticalSettings(@TempDir Path dir) throws IOException {
        Path secretFile = Files.writeString(dir.resolve("secret"), SECRET);
        for (JsonNode one : cases()) {
            EngineAuth engine = AuthSettingsReader.fromEnv(environment(one.get("env")));
            String text = document(one.get("section").asText(), secretFile);

            MetadataDocumentParser.Checked checked = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo", engine)
                    .checkWithNotes(text, 1, name -> null);

            assertThat(checked.notes()).as(one.get("name").asText()).extracting(MetadataDocumentParser.Note::code)
                    .containsExactly("jwt_identical_remove_it");
        }
    }

    @Test
    @DisplayName("a line dropped from the console's output makes the section different, so the document is refused")
    void aDroppedLineIsDifferent(@TempDir Path dir) throws IOException {
        Path secretFile = Files.writeString(dir.resolve("secret"), SECRET);
        JsonNode withIssuer = cases().get(1);
        Map<String, String> env = environment(withIssuer.get("env"));
        env.remove("APP_AUTH_ISSUER");
        EngineAuth engine = AuthSettingsReader.fromEnv(env);
        String text = document(withIssuer.get("section").asText(), secretFile);
        MetadataDocumentParser parser = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo", engine);

        assertThat(catchThrowable(() -> parser.checkWithNotes(text, 1, name -> null)))
                .isInstanceOf(MetadataDocumentException.class).hasMessageContaining("issuer");
    }
}
