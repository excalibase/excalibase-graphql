package io.github.excalibase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforcement has one place (docs/features/permissions.md §7): only the access-plan package, the
 * permission model and source, the configuration that wires the source and the schema manager that
 * builds plans may name the permission source or the raw permission model. Controllers, compilers
 * and websocket handlers see plans and guards only.
 */
class PermissionBoundaryTest {

    private static final Pattern RAW_PERMISSIONS = Pattern.compile("\\b(PermissionProvider|PermissionSet|RolePermissions)\\b");

    /** From these packages only the two permission failures may be imported. */
    private static final Pattern PERMISSION_IMPORT = Pattern.compile(
            "import io\\.github\\.excalibase\\.permissions\\.(?!PermissionEvaluationException;|PermissionsUnavailableException;)");

    private static final List<String> ALLOWED = List.of(
            "/io/github/excalibase/permissions/",
            "/io/github/excalibase/access/",
            "/io/github/excalibase/config/JwtSecurityConfig.java",
            "/io/github/excalibase/schema/GraphqlSchemaManager.java");

    private static final List<String> SURFACES = List.of(
            "/io/github/excalibase/controller/",
            "/io/github/excalibase/rest/",
            "/io/github/excalibase/compiler/",
            "/io/github/excalibase/postgres/",
            "/io/github/excalibase/mysql/",
            "/io/github/excalibase/config/ws/",
            "/io/github/excalibase/security/");

    @Test
    void onlyThePlanBuildersNameThePermissionSourceOrModel() {
        List<String> offenders = mainSources()
                .filter(file -> ALLOWED.stream().noneMatch(unixPath(file)::contains))
                .filter(file -> RAW_PERMISSIONS.matcher(read(file)).find())
                .map(Path::toString)
                .toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    void surfacesImportNothingFromThePermissionModelButItsFailures() {
        List<String> offenders = mainSources()
                .filter(file -> SURFACES.stream().anyMatch(unixPath(file)::contains))
                .filter(file -> PERMISSION_IMPORT.matcher(read(file)).find())
                .map(Path::toString)
                .toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    void theScanSeesTheSources() {
        assertThat(mainSources().filter(file -> unixPath(file).endsWith("/access/AccessPlan.java"))).hasSize(1);
        assertThat(mainSources().filter(file -> unixPath(file).endsWith("/controller/GraphqlController.java")))
                .hasSize(1);
    }

    private static Stream<Path> mainSources() {
        Path modules = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(modules)) {
            return files.filter(file -> unixPath(file).contains("/src/main/java/"))
                    .filter(file -> file.toString().endsWith(".java"))
                    .toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String unixPath(Path file) {
        return file.toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
