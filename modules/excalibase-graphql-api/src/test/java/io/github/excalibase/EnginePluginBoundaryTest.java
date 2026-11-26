package io.github.excalibase;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.excalibase.spi.QueryEngineProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the slim images slim (EXC-799). A database plugin is whatever package holds a {@link SqlEngineProvider} or a
 * {@link QueryEngineProvider}; the
 * app, REST, RLS and starter code reach it only through that interface, and no one but a plugin touches a driver.
 * Otherwise a build without one plugin would not start, or would carry it in through the other code.
 */
class EnginePluginBoundaryTest {

    private static final String[] DRIVERS = {
            "org.postgresql..", "com.mysql..", "org.sqlite..", "com.mongodb..", "org.mongodb.."};

    private static JavaClasses engine;
    private static String[] pluginPackages;

    @BeforeAll
    static void importEngine() {
        engine = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.excalibase");
        pluginPackages = pluginPackageNames(engine).stream().map(p -> p + "..").toArray(String[]::new);
    }

    private static Set<String> pluginPackageNames(JavaClasses classes) {
        return classes.stream()
                .filter(c -> (c.isAssignableTo(SqlEngineProvider.class) || c.isAssignableTo(QueryEngineProvider.class)) && !c.isInterface())
                .map(JavaClass::getPackageName)
                .collect(Collectors.toSet());
    }

    @Test
    void thePluginsAreFoundSoTheRulesBelowAreNotVacuous() {
        assertThat(pluginPackageNames(engine)).contains("io.github.excalibase.postgres", "io.github.excalibase.mysql", "io.github.excalibase.sqlite",
                "io.github.excalibase.mongodb");
    }

    @Test
    void nothingOutsideAPluginDependsOnAPluginOrADriver() {
        noClasses().that().resideOutsideOfPackages(pluginPackages)
                .should().dependOnClassesThat().resideInAnyPackage(pluginPackages)
                .as("code outside the database plugins reaches them only through SqlEngineProvider")
                .check(engine);
        noClasses().that().resideOutsideOfPackages(pluginPackages)
                .should().dependOnClassesThat().resideInAnyPackage(DRIVERS)
                .as("code outside the database plugins never touches a database driver class")
                .check(engine);
    }

    @Test
    void aPluginDoesNotDependOnAnotherPlugin() {
        List<String> plugins = List.of(pluginPackages);
        for (String own : plugins) {
            String[] others = plugins.stream().filter(p -> !p.equals(own)).toArray(String[]::new);
            noClasses().that().resideInAPackage(own)
                    .should().dependOnClassesThat().resideInAnyPackage(others)
                    .as(own + " stands alone from the other plugins")
                    .check(engine);
        }
    }
}
