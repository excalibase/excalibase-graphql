package io.github.excalibase.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The console's own code: nothing that turns server text into markup or code, and no place the secret could rest. */
class ConsoleSourceTest {

    private static final List<String> SCRIPTS = List.of("app.js", "api.js", "dom.js", "views.js");

    private static String read(String name) throws IOException {
        try (InputStream in = ConsoleSourceTest.class.getResourceAsStream("/console/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("no script writes markup, evaluates text, or keeps the secret in a URL, a cookie or localStorage")
    void scriptsAreSafe() throws IOException {
        for (String name : SCRIPTS) {
            assertThat(read(name)).as(name)
                    .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(",
                            "new Function", "localStorage", "document.cookie", "XMLHttpRequest", "import(")
                    .doesNotContainPattern("https?://");
        }
    }

    @Test
    @DisplayName("the secret goes out in the request header and nowhere else")
    void secretOnlyInTheHeader() throws IOException {
        String api = read("api.js");

        assertThat(api).contains("X-Excalibase-Admin-Secret", "sessionStorage");
        assertThat(api).doesNotContain("?secret").doesNotContain("location.");
    }
}
