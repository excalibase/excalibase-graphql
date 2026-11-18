package io.github.excalibase.admin;

import io.github.excalibase.metadata.DocumentValidation;
import io.github.excalibase.metadata.MetadataDocumentParser;
import io.github.excalibase.schema.ReservedSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class DocumentValidationTest {

    private static final ReservedSchemas RESERVED = ReservedSchemas.fromConfig(null);

    private static final String GOOD = """
            format: 1
            projectId: demo
            tables:
              - table: public.orders
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static DocumentValidation.Result validate(String text, boolean envJwt) {
        return DocumentValidation.validate(bytes(text), RESERVED, "demo", envJwt);
    }

    @Test
    void aGoodDocumentIsValidAndCounted() {
        DocumentValidation.Result result = validate(GOOD, false);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.tables()).isEqualTo(1);
        assertThat(result.functions()).isZero();
    }

    @Test
    void aBadDocumentReportsTheParsersOwnError() {
        DocumentValidation.Result result = validate("format: 1\nprojectId: [unclosed\n", false);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(1).allSatisfy(error -> assertThat(error).isNotBlank());
    }

    @Test
    void aDocumentForAnotherProjectIsRefused() {
        DocumentValidation.Result result = validate(GOOD.replace("demo", "other"), false);

        assertThat(result.valid()).isFalse();
    }

    @Test
    void aRuleNamingAReservedSchemaIsRefused() {
        DocumentValidation.Result result = validate(GOOD.replace("public.orders", "excalibase_meta.t"), false);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors().getFirst()).contains("excalibase_meta");
    }

    @Test
    void aJwtSectionIsRefusedWhenTheEngineHasTokenSettingsOfItsOwn() {
        String withJwt = GOOD.replace("tables:", """
                jwt:
                  issuer: https://idp.example.com/
                  audience: a
                  jwksUrl: https://idp.example.com/jwks.json
                  algorithms: [RS256]
                  claims:
                    role: role
                    defaultRole: user
                    userId: sub
                tables:""");

        assertThat(validate(withJwt, true).valid()).isFalse();
        assertThat(validate(withJwt, false).valid()).isTrue();
    }

    @Test
    void aBodyThatIsNotUtf8IsRefused() {
        DocumentValidation.Result result = DocumentValidation.validate(new byte[] {(byte) 0xC3, (byte) 0x28}, RESERVED,
                "demo", false);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors().getFirst()).contains("UTF-8");
    }

    @Test
    void aBodyOverTheSizeLimitIsRefusedWithoutBeingParsed() {
        byte[] huge = new byte[MetadataDocumentParser.MAX_BYTES + 1];
        Arrays.fill(huge, (byte) 'a');

        DocumentValidation.Result result = DocumentValidation.validate(huge, RESERVED, "demo", false);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors().getFirst()).contains("too large");
    }

    @Test
    void anEmptyBodyIsRefused() {
        assertThat(DocumentValidation.validate(new byte[0], RESERVED, "demo", false).valid()).isFalse();
    }

    @Test
    void theAnswerIsAMapForTheApi() {
        assertThat(validate(GOOD, false).toMap()).containsEntry("valid", true).containsEntry("tables", 1);
        assertThat(validate("not: [", false).toMap()).containsEntry("valid", false).containsKey("errors");
    }

    @Test
    void aValidDocumentComesBackAsItsTree() {
        Map<String, Object> answer = DocumentValidation.tree(bytes(GOOD), RESERVED, "demo", false);

        assertThat(answer).containsEntry("valid", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = (Map<String, Object>) answer.get("tree");
        assertThat(tree).containsEntry("format", 1).containsEntry("projectId", "demo");
        assertThat((List<?>) tree.get("tables")).hasSize(1);
    }

    @Test
    void anInvalidDocumentHasErrorsAndNoTree() {
        Map<String, Object> answer = DocumentValidation.tree(bytes(GOOD.replace("demo", "other")), RESERVED, "demo", false);

        assertThat(answer).containsEntry("valid", false).doesNotContainKey("tree");
        assertThat((List<?>) answer.get("errors")).isNotEmpty();
    }

    private static String withSecret(String reference) {
        return GOOD.replace("tables:", "jwt:\n  secret: " + reference + "\n  audience: a\ntables:");
    }

    @Test
    void aSecretReferenceThatCannotBeLoadedIsRefusedWithTheEnginesMessage(@TempDir Path dir) throws IOException {
        Path shortKey = Files.writeString(dir.resolve("short.key"), "tooshort");

        for (String reference : List.of("env:EXCALIBASE_SURELY_NOT_SET", "file:" + dir.resolve("none.key"),
                "file:" + shortKey)) {
            DocumentValidation.Result result = validate(withSecret(reference), false);

            assertThat(result.valid()).as(reference).isFalse();
            assertThat(result.errors()).singleElement().asString()
                    .startsWith("jwt: the signing keys or the secret cannot be loaded")
                    .doesNotContain("tooshort");
        }
    }

    @Test
    void aSecretFileOfEnoughCharactersIsValid(@TempDir Path dir) throws IOException {
        Path key = Files.writeString(dir.resolve("long.key"), "k".repeat(32) + "\n");

        assertThat(validate(withSecret("file:" + key), false).valid()).isTrue();
    }

    @Test
    void theGivenEnvironmentIsWhatAnEnvSecretIsReadFrom() {
        String document = withSecret("env:JWT_SECRET");

        assertThat(DocumentValidation.validate(bytes(document), RESERVED, "demo", false, name -> null).valid())
                .isFalse();
        assertThat(DocumentValidation.validate(bytes(document), RESERVED, "demo", false,
                name -> "k".repeat(32)).valid()).isTrue();
    }

    @Test
    void theFourArgumentOverloadReadsTheProcessEnvironment() {
        String path = System.getenv("PATH");
        assumeThat(path).isNotNull();
        assumeThat(path.strip().length()).isGreaterThanOrEqualTo(32);

        assertThat(validate(withSecret("env:PATH"), false).valid()).isTrue();
    }
}
