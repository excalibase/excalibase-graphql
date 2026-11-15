package io.github.excalibase.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.opentelemetry.api.OpenTelemetry;
import org.postgresql.Driver;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the dependency versions that carry published High/Medium advisories, so a BOM change
 * cannot quietly move them back. Versions are read from the jars on the classpath.
 */
class PatchedDependenciesTest {

    @Test
    void jacksonCoreIsPatched() {
        assertAtLeast(JsonFactory.class, "jackson-core", "2.21.7");
    }

    @Test
    void jacksonDatabindIsPatched() {
        assertAtLeast(ObjectMapper.class, "jackson-databind", "2.21.7");
    }

    @Test
    void jacksonDatatypesAreAlignedWithTheCore() {
        assertAtLeast(JavaTimeModule.class, "jackson-datatype-jsr310", "2.21.7");
        assertAtLeast(Jdk8Module.class, "jackson-datatype-jdk8", "2.21.7");
    }

    @Test
    void openTelemetryApiIsPatched() {
        assertAtLeast(OpenTelemetry.class, "opentelemetry-api", "1.67.0");
    }

    @Test
    void postgresDriverIsPatched() {
        assertAtLeast(Driver.class, "postgresql", "42.7.12");
    }

    @Test
    void testScopeAssertjIsPatched() {
        assertAtLeast(Assertions.class, "assertj-core", "3.27.7");
    }

    @Test
    void testScopeCommonsLangIsPatched() {
        assertAtLeast(StringUtils.class, "commons-lang3", "3.18.0");
    }

    private static void assertAtLeast(Class<?> marker, String artifact, String minimum) {
        String actual = versionOf(marker, artifact);
        assertTrue(compare(actual, minimum) >= 0,
                artifact + " is " + actual + ", needs " + minimum + " or later");
    }

    /** The version in the jar file name, e.g. opentelemetry-api-1.67.0.jar. */
    private static String versionOf(Class<?> marker, String artifact) {
        String jar = Path.of(URI.create(marker.getProtectionDomain().getCodeSource().getLocation().toString()))
                .getFileName().toString();
        assertTrue(jar.startsWith(artifact + "-") && jar.endsWith(".jar"), "unexpected jar " + jar);
        return jar.substring(artifact.length() + 1, jar.length() - ".jar".length());
    }

    private static int compare(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && a[i].matches("\\d+") ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && b[i].matches("\\d+") ? Integer.parseInt(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
