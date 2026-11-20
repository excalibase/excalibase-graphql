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

    private static final List<String> SCRIPTS = List.of("app.js", "api.js", "dom.js", "views.js", "editor.js",
            "grid.js", "jwt.js", "docmodel.js", "diff.js", "history.js");
    private static final List<String> OUTSIDE_DOM = List.of("app.js", "api.js", "views.js", "editor.js", "grid.js",
            "jwt.js", "docmodel.js", "diff.js", "history.js");

    /**
     * The page's own {@code document} used as an identifier: member access, an alias, an argument, or reached through
     * the window. A property name ({@code reply.document}) and a destructured key ({@code { document }}) are not.
     */
    static final Pattern DOCUMENT_IDENTIFIER = Pattern.compile(
            "(?<![.\\w$])document\\s*[.\\[;)]|=\\s*document\\b|(?:window|self|globalThis|top|parent)\\s*(?:\\.\\s*document|\\[)"
                    + "|\\[\\s*[\"'`]document");

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
        for (String name : OUTSIDE_DOM) {
            assertThat(read(name)).as(name)
                    .doesNotContainPattern(DOCUMENT_IDENTIFIER.pattern())
                    .doesNotContain("createElement", "createTextNode", "setAttribute", "appendChild",
                            "replaceChildren", "textContent", "addEventListener", ".append(", ".prepend(", ".after(",
                            ".before(", ".replaceWith(", ".remove(", ".close(", ".showModal(", "Range", "Node")
                    .doesNotContainPattern("h\\(\"(script|iframe|object|embed|link|meta|base|style|template)\"");
        }
        String dom = read("dom.js");
        assertThat(dom).contains("createElement(tag)", "createTextNode(String(child))")
                .doesNotContain("textContent")
                .doesNotContainPattern("\\]\\s*=[^=]").doesNotContainPattern("\\.[A-Za-z_]\\w*\\s*=[^=]")
                .doesNotContain("Object.assign", "defineProperty", "setAttributeNS", "setAttributeNode");
        assertThat(count(dom, "setAttribute(")).as("h() once, and the download link's href and download").isEqualTo(3);
        assertThat(count(dom, "document.")).isEqualTo(5);
    }

    @Test
    @DisplayName("the document pattern bans the page object however it is reached, and lets the server's field through")
    void documentPatternMatchesIdentifiersOnly() {
        for (String banned : List.of("document.createElement('a')", "const d = document;", "const d =document",
                "use(document)", "window.document.body", "globalThis['document']", "x[\"document\"]",
                "document [0]", "window . document")) {
            assertThat(DOCUMENT_IDENTIFIER.matcher(banned).find()).as(banned).isTrue();
        }
        for (String allowed : List.of("reply.document", "const { document: text, ...rest } = await call();",
                "const { document } = reply;", "body.document ?? null", "{ document: text }",
                "// the document is saved", "documentation")) {
            assertThat(DOCUMENT_IDENTIFIER.matcher(allowed).find()).as(allowed).isFalse();
        }
    }

    @Test
    @DisplayName("a download is a Blob link built in dom.js from a fixed name; no other script can make one")
    void downloadsOnlyThroughDomJs() throws IOException {
        String dom = read("dom.js");

        assertThat(dom).contains("URL.createObjectURL(new Blob(", "setAttribute(\"href\", url)",
                "setAttribute(\"download\", filename)", "URL.revokeObjectURL(url)");
        assertThat(count(dom, "createObjectURL")).isEqualTo(1);
        for (String name : OUTSIDE_DOM) {
            assertThat(read(name)).as(name).doesNotContain("Blob", "createObjectURL", "href", "download", ".click(")
                    .doesNotContainPattern("\\bsaveAs\\([^)]*\\+");
        }
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
    @DisplayName("api.js reads the server's document field as a field, with no spelling workaround")
    void apiReadsTheDocumentField() throws IOException {
        String api = read("api.js");

        assertThat(api).contains("document").doesNotContain("TEXT_FIELD", "\"docu\"", "+ \"ment\"")
                .doesNotContainPattern(DOCUMENT_IDENTIFIER.pattern());
    }

    @Test
    @DisplayName("a download's name is only ever what exportName chose, never server data")
    void downloadNameIsFixed() throws IOException {
        String app = read("app.js");

        assertThat(app).contains("const { filename, type } = exportName(text, label);", "saveAs(filename, text, type);");
        assertThat(count(app, "saveAs(")).isEqualTo(1);
    }

    @Test
    @DisplayName("the secret goes out in the request header and nowhere else")
    void secretOnlyInTheHeader() throws IOException {
        String api = read("api.js");

        assertThat(api).contains("X-Excalibase-Admin-Secret", "sessionStorage").doesNotContain("?secret", "location.");
    }
}
