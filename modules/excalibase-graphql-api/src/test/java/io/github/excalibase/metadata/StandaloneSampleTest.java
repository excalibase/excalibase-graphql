package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The sample document people copy from deploy/standalone must itself be valid. */
class StandaloneSampleTest {

    @Test
    void theSamplePermissionsFile_validates() {
        Path here = Path.of("").toAbsolutePath();
        while (!Files.exists(here.resolve("deploy/standalone/metadata/permissions.yaml"))) {
            here = here.getParent();
        }
        Path sample = here.resolve("deploy/standalone/metadata/permissions.yaml");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = ValidateCommand.run(new String[] {"validate", sample.toString(), "--project-id", "my-app"},
                Map.of(), new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));

        assertThat(code).as(err.toString()).isZero();
    }
}
