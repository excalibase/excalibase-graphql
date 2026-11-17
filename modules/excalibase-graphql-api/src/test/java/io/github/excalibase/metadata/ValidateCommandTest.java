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
}
