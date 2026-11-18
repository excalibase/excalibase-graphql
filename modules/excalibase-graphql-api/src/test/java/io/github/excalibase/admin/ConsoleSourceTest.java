package io.github.excalibase.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                    .doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "writeln",
                            "createContextualFragment", "createRange", "srcdoc", "setHTML", "DOMParser",
                            "parseFromString", "eval(", "Function(", "localStorage", "document.cookie",
                            "XMLHttpRequest", "import(", "javascript:", "setTimeout(", "setInterval(")
                    .doesNotContainPattern("https?://");
        }
    }

    @Test
    @DisplayName("dom.js is the only script that touches the DOM, and it only makes elements, text and listed attributes")
    void onlyDomJsTouchesTheDom() throws IOException {
        for (String name : List.of("app.js", "api.js", "views.js")) {
            assertThat(read(name)).as(name)
                    .doesNotContainPattern("\\bdocument\\b")
                    .doesNotContain("createElement", "createTextNode", "setAttribute", "appendChild",
                            "replaceChildren", "textContent", "addEventListener", ".append(", ".prepend(", ".after(",
                            ".before(", ".replaceWith(", "Range", "Node")
                    .doesNotContainPattern("h\\(\"(script|iframe|object|embed|link|meta|base|style|template)\"");
        }
        String dom = read("dom.js");
        assertThat(dom).contains("createElement(tag)", "createTextNode(String(child))")
                .doesNotContain("textContent");
        assertThat(dom).doesNotContainPattern("\\]\\s*=[^=]").doesNotContainPattern("\\.[A-Za-z_]\\w*\\s*=[^=]")
                .doesNotContain("Object.assign", "defineProperty", "setAttributeNS", "setAttributeNode");
        assertThat(count(dom, "setAttribute(")).isEqualTo(1);
        assertThat(count(dom, "document.")).isEqualTo(3);
    }

    @Test
    @DisplayName("the attributes dom.js may set exclude every one that loads or runs something")
    void attributeAllowList() throws IOException {
        Matcher list = Pattern.compile("ATTRIBUTES = new Set\\(\\[(.*?)\\]\\)", Pattern.DOTALL).matcher(read("dom.js"));
        assertThat(list.find()).isTrue();

        assertThat(list.group(1)).doesNotContain("src", "href", "srcdoc", "style", "action", "formaction", "data", "on");
        assertThat(read("dom.js")).contains("ATTRIBUTES.has(name)");
    }

    private static int count(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("the secret goes out in the request header and nowhere else")
    void secretOnlyInTheHeader() throws IOException {
        String api = read("api.js");

        assertThat(api).contains("X-Excalibase-Admin-Secret", "sessionStorage").doesNotContain("?secret", "location.");
    }
}
