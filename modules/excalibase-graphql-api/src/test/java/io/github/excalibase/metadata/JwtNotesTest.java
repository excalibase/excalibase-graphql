package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/** The unflagged signals: deprecation, "identical, you can remove it" and "nothing verifies a token". */
class JwtNotesTest {

    private static final UnaryOperator<String> ENV = name -> AuthSettingsReaderTest.SECRET;
    private static final String RULES = "tables:\n  - table: public.orders\n    role: user\n    select:\n"
            + "      filter: {}\n      columns: \"*\"\n";
    private static final String ANON_ONLY = "tables:\n  - table: public.orders\n    role: anon\n    select:\n"
            + "      filter: {}\n      columns: \"*\"\n";
    private static final String JWT = "jwt:\n  secret: env:DOC_SECRET\n  audience: my-api\n";

    private static List<String> codes(EngineAuth auth, String body) {
        return new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo", auth)
                .checkWithNotes("format: 1\nprojectId: demo\n" + body, 1, ENV).notes().stream()
                .map(MetadataDocumentParser.Note::code).toList();
    }

    private static EngineAuth engine() {
        return AuthSettingsReader.fromEnv(Map.of("APP_AUTH_SECRET", AuthSettingsReaderTest.SECRET,
                "APP_AUTH_AUDIENCE", "my-api"));
    }

    @Test
    void aDocumentJwtWithNoEngineSettings_isDeprecated_andStillWorks() {
        assertThat(codes(EngineAuth.NONE, JWT + RULES)).containsExactly("jwt_deprecated");
    }

    @Test
    void anIdenticalSection_saysItCanBeRemoved() {
        assertThat(codes(engine(), JWT + RULES)).containsExactly("jwt_identical_remove_it");
    }

    @Test
    void rulesForARoleBesidesAnonAndNoVerifier_warnNoTokenVerifier() {
        assertThat(codes(EngineAuth.NONE, RULES)).containsExactly("no_token_verifier");
    }

    @Test
    void anonOnlyRules_needNoVerifier() {
        assertThat(codes(EngineAuth.NONE, ANON_ONLY)).isEmpty();
    }

    @Test
    void anyConfiguredVerifier_silencesIt() {
        assertThat(codes(engine(), RULES)).isEmpty();
        assertThat(codes(EngineAuth.of(true), RULES)).isEmpty();
        assertThat(codes(EngineAuth.NONE, JWT + RULES)).doesNotContain("no_token_verifier");
    }

    @Test
    void theNotesReachValidateAsWarnings_withoutRefusingTheDocument() {
        var result = DocumentValidation.validate(("format: 1\nprojectId: demo\n" + RULES).getBytes(), 
                ReservedSchemas.fromConfig(""), "demo", EngineAuth.NONE, ENV, null);

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).extracting(DocumentValidation.Warning::code).containsExactly("no_token_verifier");
        assertThat(result.warnings().get(0).role()).isNull();
    }

    @Test
    void theIdenticalSectionIsLoggedAsTheOperatorDocsName(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret"), AuthSettingsReaderTest.SECRET);
        Logger logger = (Logger) LoggerFactory.getLogger(MetadataDocumentParser.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo", engine()).load(
                    "format: 1\nprojectId: demo\njwt:\n  secret: file:" + secret + "\n  audience: my-api\n" + RULES, 1);
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.startsWith("metadata_jwt_identical "));
    }
}
