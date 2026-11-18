package io.github.excalibase.admin;

import io.github.excalibase.metadata.DocumentValidation;
import io.github.excalibase.metadata.MetadataDocumentParser;
import io.github.excalibase.schema.ReservedSchemas;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

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
}
