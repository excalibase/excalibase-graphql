package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which of the two consoles answers each path, and that the legacy one never leaks into the new flag's namespace. */
class ConsoleSiteTest {

    private static ConsoleSite legacy() {
        return new ConsoleSite(ConsoleUi.LEGACY, ConsoleAssets.legacy(), null);
    }

    private static ConsoleSite next() {
        return new ConsoleSite(ConsoleUi.NEXT, ConsoleAssets.legacy(),
                ConsoleAssets.bundle("/console-next", ConsoleAssets.CLASSPATH));
    }

    @Test
    void legacyServesTheOldPageAtTheRootAndHasNoLegacyAlias() {
        ConsoleSite site = legacy();

        assertThat(site.find("").orElseThrow().contentType()).startsWith("text/html");
        assertThat(site.find("app.js")).isPresent();
        assertThat(site.find("legacy")).isEmpty();
        assertThat(site.find("assets/index-abc123.js")).isEmpty();
        assertThat(site.find("index.html")).isPresent();
    }

    @Test
    void nextServesTheNewPageAtTheRootAndTheOldOneAtLegacy() {
        ConsoleSite site = next();

        assertThat(new String(site.find("").orElseThrow().body())).contains("assets/index-abc123.js");
        assertThat(new String(site.find("index.html").orElseThrow().body())).contains("assets/index-abc123.js");
        assertThat(new String(site.find("legacy").orElseThrow().body())).contains("/console/app.js");
    }

    @Test
    void nextStillServesTheLegacyFilesTheLegacyPageNeeds() {
        ConsoleSite site = next();

        assertThat(site.find("app.js")).isPresent();
        assertThat(site.find("style.css")).isPresent();
        assertThat(site.find("assets/index-abc123.js")).isPresent();
    }

    @Test
    void isServedFollowsTheSameAllowlistAsTheController() {
        ConsoleSite site = next();

        assertThat(site.isServed("/console")).isTrue();
        assertThat(site.isServed("/console/legacy")).isTrue();
        assertThat(site.isServed("/console/assets/index-abc123.js")).isTrue();
        assertThat(site.isServed("/console/.vite/manifest.json")).isFalse();
        assertThat(site.isServed("/console/assets/../application.yml")).isFalse();
        assertThat(site.isServed("/console/assets/%2e%2e/application.yml")).isFalse();
        assertThat(site.isServed("/console/")).isFalse();
        assertThat(site.isServed("/consolex")).isFalse();
        assertThat(site.isServed(null)).isFalse();
        assertThat(legacy().isServed("/console/legacy")).isFalse();
        assertThat(legacy().isServed("/console/assets/index-abc123.js")).isFalse();
    }

    @Test
    void nextWithoutABundleRefusesTheStart() {
        assertThatThrownBy(() -> ConsoleAssets.bundle("/console-absent", ConsoleAssets.CLASSPATH))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("console-absent");
        assertThat(ConsoleSite.legacyOnly().find("")).isPresent();
    }

    @Test
    void withNothingSetTheBuiltConsoleIsServedWhenTheJarHasIt() {
        assertThat(ConsoleSite.defaultUi(ConsoleAssets.CLASSPATH)).isEqualTo(ConsoleUi.NEXT);
    }

    @Test
    void withNothingSetAJarBuiltWithoutTheConsoleServesTheLegacyOne() {
        ConsoleAssets.Source empty = new ConsoleAssets.Source() {
            @Override
            public boolean exists(String resource) {
                return false;
            }

            @Override
            public byte[] read(String resource) {
                throw new IllegalStateException("nothing to read");
            }
        };

        assertThat(ConsoleSite.defaultUi(empty)).isEqualTo(ConsoleUi.LEGACY);
    }

    private static final ConsoleAssets.Source NO_CONSOLE = new ConsoleAssets.Source() {
        @Override
        public boolean exists(String resource) {
            return false;
        }

        @Override
        public byte[] read(String resource) {
            throw new IllegalStateException("nothing to read");
        }
    };

    @Test
    void theConstructorServesTheBuiltConsoleWhenNothingIsSetAndTheJarHasIt() {
        ConsoleSite site = new ConsoleSite(null, ConsoleAssets.CLASSPATH);

        assertThat(new String(site.find("").orElseThrow().body())).contains("assets/index-abc123.js");
        assertThat(site.find("legacy")).isPresent();
    }

    @Test
    void theConstructorServesTheLegacyConsoleWhenNothingIsSetAndTheJarLacksTheBuild() {
        ConsoleSite site = new ConsoleSite(null, NO_CONSOLE);

        assertThat(new String(site.find("").orElseThrow().body())).contains("/console/app.js");
        assertThat(site.find("legacy")).isEmpty();
        assertThat(site.find("assets/index-abc123.js")).isEmpty();
    }

    @Test
    void theConstructorObeysAnExplicitChoiceWhateverTheJarHolds() {
        assertThat(new ConsoleSite("legacy", ConsoleAssets.CLASSPATH).find("legacy")).isEmpty();
        assertThat(new ConsoleSite("next", ConsoleAssets.CLASSPATH).find("legacy")).isPresent();
        assertThatThrownBy(() -> new ConsoleSite("next", NO_CONSOLE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ConsoleSite("", ConsoleAssets.CLASSPATH)).isInstanceOf(IllegalStateException.class);
    }
}
