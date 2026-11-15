package io.github.excalibase.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code app.security.jwt-enabled} is parsed once, in {@link JwtEnabledSetting}. Code that reads the property
 * itself would parse it its own way, so no source but that class may.
 */
class JwtEnabledSingleSourceTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    @DisplayName("no code but JwtEnabledSetting reads app.security.jwt-enabled as a value")
    void onlyTheSettingReadsTheProperty() throws IOException {
        List<String> readers;
        try (Stream<Path> files = Files.walk(MAIN)) {
            readers = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("JwtEnabledSetting.java"))
                    .filter(JwtEnabledSingleSourceTest::readsTheProperty)
                    .map(Path::toString).toList();
        }

        assertThat(readers).as("sources that read the property themselves").isEmpty();
    }

    private static boolean readsTheProperty(Path path) {
        try {
            String source = Files.readString(path);
            for (String line : source.split("\n")) {
                boolean mentions = line.contains("app.security.jwt-enabled") || line.contains("jwt-enabled");
                boolean isRead = line.contains("@Value") || line.contains("getProperty") || line.contains("getRequiredProperty");
                if (mentions && isRead) {
                    return true;
                }
            }
            return false;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
