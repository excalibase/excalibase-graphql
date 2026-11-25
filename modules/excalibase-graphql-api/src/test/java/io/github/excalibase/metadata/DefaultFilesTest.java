package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every default the repository ships for a standalone engine: its permissions go through the real {@code validate}
 * and hold no {@code jwt:} section and no tutorial comment, and every example that runs the engine sets the token
 * settings in the engine's own configuration, so a fresh standalone still verifies tokens.
 */
class DefaultFilesTest {

    private static final Path STANDALONE = root().resolve("deploy/standalone");
    private static final Path PERMISSIONS = STANDALONE.resolve("metadata/permissions.yaml");
    private static final Path SQLITE_PERMISSIONS = STANDALONE.resolve("sqlite/metadata/permissions.yaml");
    private static final Path CONFIGMAP = STANDALONE.resolve("kubernetes/configmap-file-source.yaml");
    private static final Path DB_SOURCE = STANDALONE.resolve("kubernetes/db-source.yaml");
    private static final List<Path> COMPOSE = List.of(STANDALONE.resolve("docker-compose.file.yml"),
            STANDALONE.resolve("docker-compose.url.yml"), STANDALONE.resolve("sqlite/docker-compose.yml"));
    private static final String SECRET_NAME = "APP_AUTH_SECRET";
    private static final String AUDIENCE_NAME = "APP_AUTH_AUDIENCE";
    private static final String ENV_PREFIX = "APP_AUTH_";
    private static final String OLD_SECRET = "JWT_SECRET";
    private static final String A_SECRET = "a-secret-the-secret-key-ref-would-give-32-bytes";

    private static Path root() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.isDirectory(here.resolve("deploy/standalone"))) {
            here = here.getParent();
        }
        return here;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(file.toString(), e);
        }
    }

    private static List<Object> documents(Path file) {
        List<Object> found = new ArrayList<>();
        new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(read(file)).forEach(found::add);
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /** The permissions each default file holds, as the engine would read them. */
    private static Map<Path, String> permissions() {
        Map<Path, String> texts = new LinkedHashMap<>();
        texts.put(PERMISSIONS, read(PERMISSIONS));
        texts.put(SQLITE_PERMISSIONS, read(SQLITE_PERMISSIONS));
        documents(CONFIGMAP).stream().map(DefaultFilesTest::map)
                .filter(document -> "ConfigMap".equals(document.get("kind")))
                .forEach(document -> texts.put(CONFIGMAP, String.valueOf(map(document.get("data")).get("permissions.yaml"))));
        return texts;
    }

    @Test
    @DisplayName("every shipped permissions default passes the real validate and has no jwt: section")
    void defaultsValidate() {
        permissions().forEach((file, text) -> {
            DocumentValidation.Result result = DocumentValidation.validate(text.getBytes(StandardCharsets.UTF_8),
                    ReservedSchemas.fromConfig(""), "my-app", EngineAuth.NONE, name -> A_SECRET, null);

            assertThat(result.valid()).as(file + " " + result.errors()).isTrue();
            assertThat(text).as(file.toString()).doesNotContain("jwt:").doesNotContain("JWT_SECRET");
        });
        assertThat(permissions()).hasSize(3);
    }

    @Test
    @DisplayName("the standalone and sqlite defaults hold no comment at all")
    void noComments() {
        assertThat(read(PERMISSIONS).lines().filter(line -> line.contains("#"))).isEmpty();
        assertThat(read(SQLITE_PERMISSIONS).lines().filter(line -> line.contains("#"))).isEmpty();
    }

    @Test
    @DisplayName("the defaults validate as the engine reads them under engine token settings too (no jwt: to refuse)")
    void validWithEngineSettings() {
        EngineAuth auth = AuthSettingsReader.fromEnv(Map.of(SECRET_NAME, A_SECRET, AUDIENCE_NAME, "my-app-api"));

        permissions().forEach((file, text) -> assertThat(DocumentValidation.validate(
                text.getBytes(StandardCharsets.UTF_8), ReservedSchemas.fromConfig(""), "my-app", auth, name -> null, null)
                .valid()).as(file.toString()).isTrue());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> composeEnvironment(Path file) {
        Map<String, Object> services = map(map(documents(file).getFirst()).get("services"));
        Map<String, Object> environment = map(map(services.get("engine")).get("environment"));
        Map<String, String> text = new LinkedHashMap<>();
        environment.forEach((name, value) -> text.put(name, String.valueOf(value)));
        return text;
    }

    @Test
    @DisplayName("every compose example sets APP_AUTH_* that the engine accepts, and not the old JWT_SECRET")
    void composeSetsEngineTokenSettings() {
        for (Path file : COMPOSE) {
            Map<String, String> environment = composeEnvironment(file);

            assertThat(environment).as(file.toString()).doesNotContainKey(OLD_SECRET).containsKey(SECRET_NAME)
                    .containsKey(AUDIENCE_NAME);
            EngineAuth auth = AuthSettingsReader.fromEnv(withoutOtherNames(environment));
            assertThat(auth.engine()).as(file.toString()).isTrue();
        }
    }

    private static Map<String, String> withoutOtherNames(Map<String, String> environment) {
        Map<String, String> auth = new LinkedHashMap<>();
        environment.forEach((name, value) -> {
            if (name.startsWith(ENV_PREFIX)) {
                auth.put(name, value);
            }
        });
        return auth;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> kubernetesEnvironment(Path file, String kind) {
        Map<String, String> found = new LinkedHashMap<>();
        for (Object document : documents(file)) {
            Map<String, Object> manifest = map(document);
            if (!kind.equals(manifest.get("kind"))) {
                continue;
            }
            Map<String, Object> pod = map(map(map(manifest.get("spec")).get("template")).get("spec"));
            for (Object container : (List<Object>) pod.get("containers")) {
                for (Object variable : (List<Object>) map(container).getOrDefault("env", List.of())) {
                    Map<String, Object> entry = map(variable);
                    found.put(String.valueOf(entry.get("name")),
                            entry.get("value") == null ? A_SECRET : String.valueOf(entry.get("value")));
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("the kubernetes examples set APP_AUTH_* the engine accepts, and not the old JWT_SECRET")
    void kubernetesSetsEngineTokenSettings() {
        for (Path file : List.of(CONFIGMAP, DB_SOURCE)) {
            Map<String, String> environment = kubernetesEnvironment(file, "Deployment");

            assertThat(environment).as(file.toString()).doesNotContainKey(OLD_SECRET).containsKey(SECRET_NAME)
                    .containsKey(AUDIENCE_NAME);
            assertThat(AuthSettingsReader.fromEnv(withoutOtherNames(environment)).engine()).as(file.toString()).isTrue();
        }
    }

    @Test
    @DisplayName("the standalone README and docs say how to move from the old JWT_SECRET compose")
    void upgradeNote() {
        String readme = read(STANDALONE.resolve("README.md"));

        assertThat(readme).contains(SECRET_NAME).contains("no_token_verifier");
    }
}
