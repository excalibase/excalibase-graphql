package io.github.excalibase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application scans the packages that hold its components rather than every class under the root
 * package, which costs startup time on a small CPU. A component added in a package that is not listed would
 * silently never be created, so this fails instead.
 */
class ComponentScanCoverageTest {

    private static String[] scannedPackages() {
        return ExcalibaseGraphqlApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages();
    }

    @Test
    void theScanIsNarrowerThanTheRootPackage() {
        assertThat(scannedPackages()).isNotEmpty().doesNotContain("io.github.excalibase");
    }

    @Test
    void everyComponentOfTheApplicationIsInAScannedPackage() {
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(true);
        String[] scanned = scannedPackages();

        List<String> outside = provider.findCandidateComponents("io.github.excalibase").stream()
                .filter(definition -> !String.valueOf(definition.getSource()).contains("test-classes"))
                .map(BeanDefinition::getBeanClassName)
                .filter(name -> !name.equals(ExcalibaseGraphqlApplication.class.getName()))
                .filter(name -> Arrays.stream(scanned).noneMatch(pkg -> name.startsWith(pkg + ".")))
                .toList();

        assertThat(outside).as("components outside the scanned packages").isEmpty();
    }
}
