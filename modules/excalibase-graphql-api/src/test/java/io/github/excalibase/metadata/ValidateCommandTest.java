package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class ValidateCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(Map<String, String> env, String... args) {
        return ValidateCommand.run(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private Path file(String content) throws IOException {
        return Files.writeString(dir.resolve("permissions.yaml"), content);
    }

    @Test
    void aGoodDocument_exitsZero() throws IOException {
        Path file = file(GOOD_YAML);

        assertThat(run(Map.of(), "validate", file.toString())).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("ok").contains(file.toString());
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void aBadRule_exitsOne_andNamesThePathOfTheError() throws IOException {
        Path file = file(BAD_RULE);

        assertThat(run(Map.of(), "validate", file.toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(file.toString())
                .contains("tables[0].select.filter");
    }

    @Test
    void aSyntaxError_exitsOne_withTheLine() throws IOException {
        assertThat(run(Map.of(), "validate", file(BAD_SYNTAX).toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("line");
    }

    @Test
    void aReservedSchema_exitsOne() throws IOException {
        assertThat(run(Map.of(), "validate", file(BAD_RESERVED).toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("reserved");
    }

    @Test
    void aFileThatCannotBeRead_exitsTwo() {
        assertThat(run(Map.of(), "validate", dir.resolve("nope.yaml").toString())).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("nope.yaml");
    }

    @Test
    void wrongUsage_exitsTwo_withTheUsage() {
        assertThat(run(Map.of(), "validate")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage");
        assertThat(run(Map.of(), "validate", "a", "b")).isEqualTo(2);
        assertThat(run(Map.of(), "frobnicate", "a")).isEqualTo(2);
    }

    @Test
    void theProjectIsCheckedWhenGiven_byFlagOrByTheEnginesOwnVariable() throws IOException {
        Path file = file(GOOD_YAML);

        assertThat(run(Map.of(), "validate", file.toString(), "--project-id", "other")).isEqualTo(1);
        assertThat(run(Map.of("APP_PROJECT_ID", "other"), "validate", file.toString())).isEqualTo(1);
        assertThat(run(Map.of("APP_PROJECT_ID", "demo"), "validate", file.toString())).isZero();
    }

    @Test
    void theEnginesReservedSchemaSetting_isHonoured() throws IOException {
        Path file = file(GOOD_YAML.replace("public.orders", "legacy.orders"));

        assertThat(run(Map.of(), "validate", file.toString())).isZero();
        assertThat(run(Map.of("APP_RESERVED_SCHEMAS", "legacy"), "validate", file.toString())).isEqualTo(1);
    }

    @Test
    void aFolderHoldingPermissionsYaml_isAccepted_asTheEngineAcceptsIt() throws IOException {
        file(GOOD_YAML);

        assertThat(run(Map.of(), "validate", dir.toString())).isZero();
    }

    @Test
    void aFileOverOneMiB_isInvalid_withoutBeingReadWhole() throws IOException {
        Path file = file(GOOD_YAML + "# " + "x".repeat(MetadataDocumentParser.MAX_BYTES * 2) + "\n");

        assertThat(run(Map.of(), "validate", file.toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("1 MiB");
    }

    @Test
    void inputsThatUsedToEscapeAsExceptions_exitOneCleanly() throws IOException {
        assertThat(run(Map.of(), "validate", file(GOOD_YAML + "~: x\n").toString())).isEqualTo(1);
        assertThat(run(Map.of(), "validate",
                file("format: 4294967297\nprojectId: demo\ntables: []\n").toString())).isEqualTo(1);
        assertThat(run(Map.of(), "validate", file("[".repeat(5000) + "]".repeat(5000)).toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("Exception");
    }

    @Test
    void aDocumentWithJwtSettings_validatesWithoutReachingTheIdentityProvider() throws IOException {
        assertThat(run(Map.of(), "validate", file(WITH_JWT).toString())).isZero();
    }

    @Test
    void aLiteralSecretInTheJwtSection_exitsOne_andNamesThePath() throws IOException {
        String document = GOOD_YAML.replace("tables:", """
                jwt:
                  secret: hunter2hunter2hunter2hunter2hunter2
                  audience: a
                tables:""");

        assertThat(run(Map.of(), "validate", file(document).toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("jwt.secret").doesNotContain("hunter2");
    }

    private static final String SECRET_REFUSAL = "jwt: the signing keys or the secret cannot be loaded";
    private static final String LONG_SECRET = "s3cr3t-".repeat(6);

    private String withSecret(String reference) {
        return GOOD_YAML.replace("tables:", "jwt:\n  secret: " + reference + "\n  audience: a\ntables:");
    }

    @Test
    void aSecretVariableThatIsNotSet_exitsOne_withTheEnginesMessage() throws IOException {
        assertThat(run(Map.of(), "validate", file(withSecret("env:JWT_SECRET")).toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(SECRET_REFUSAL);
    }

    @Test
    void aSecretVariableThatIsSet_passes_andTheValueIsNeverPrinted() throws IOException {
        Path file = file(withSecret("env:JWT_SECRET"));

        assertThat(run(Map.of("JWT_SECRET", LONG_SECRET), "validate", file.toString())).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))
                .doesNotContain(LONG_SECRET);
    }

    @Test
    void aSecretVariableThatIsTooShort_exitsOne_withoutPrintingIt() throws IOException {
        assertThat(run(Map.of("JWT_SECRET", "tooshort"), "validate", file(withSecret("env:JWT_SECRET")).toString()))
                .isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(SECRET_REFUSAL).doesNotContain("tooshort");
    }

    @Test
    void aSecretFileThatIsMissing_orTooShort_exitsOne_andALongOnePasses() throws IOException {
        Path missing = dir.resolve("none.key");
        Path shortKey = Files.writeString(dir.resolve("short.key"), "tooshort");
        Path longKey = Files.writeString(dir.resolve("long.key"), LONG_SECRET + "\n");

        assertThat(run(Map.of(), "validate", file(withSecret("file:" + missing)).toString())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(SECRET_REFUSAL);
        assertThat(run(Map.of(), "validate", file(withSecret("file:" + shortKey)).toString())).isEqualTo(1);
        assertThat(run(Map.of(), "validate", file(withSecret("file:" + longKey)).toString())).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("tooshort").doesNotContain(LONG_SECRET);
    }
}
