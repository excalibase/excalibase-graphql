package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.JwtVerificationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.excalibase.metadata.MetadataFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A new document with other JWT settings takes effect together with its rules, and a bad one changes nothing. */
class JwtReloadTest {

    private final AtomicReference<String> text = new AtomicReference<>();
    private JwksStub idp;
    private ReloadingMetadataSource source;

    @BeforeEach
    void start() {
        idp = new JwksStub();
        text.set(document("https://api.one", "public.orders"));
        source = new ReloadingMetadataSource("test",
                () -> new ReloadingMetadataSource.Raw(text.get(), "v-" + text.get().hashCode()),
                new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT)::load, () -> true,
                Duration.ofHours(1), Duration.ofMillis(1), new SimpleMeterRegistry());
        source.start();
    }

    @AfterEach
    void stop() {
        source.close();
        idp.close();
    }

    private String document(String audience, String table) {
        return "format: 1\nprojectId: demo\njwt:\n  issuer: \"" + idp.issuer() + "\"\n  jwksUrl: " + idp.jwksUrl()
                + "\n  audience: " + audience + "\ntables:\n  - table: " + table
                + "\n    role: user\n    select:\n      filter: {}\n      columns: \"*\"\n";
    }

    private String tokenFor(String audience) {
        return idp.rs256(idp.claims().claim("role", "user").audience(audience).build());
    }

    private void swapTo(String document) {
        text.set(document);
        source.refresh();
        source.refresh();
    }

    @Test
    void newJwtSettings_swapWithTheRules_inOneStep() {
        assertThat(source.tokenVerifier().orElseThrow().verify(tokenFor("https://api.one"), PROJECT).role())
                .isEqualTo("user");

        swapTo(document("https://api.two", "public.secrets"));

        assertThat(source.permissionsFor(PROJECT).tables().get(0).table()).isEqualTo("public.secrets");
        assertThat(source.tokenVerifier().orElseThrow().verify(tokenFor("https://api.two"), PROJECT).role())
                .isEqualTo("user");
        assertThatThrownBy(() -> source.tokenVerifier().orElseThrow().verify(tokenFor("https://api.one"), PROJECT))
                .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void aDocumentWhoseJwksCannotBeFetched_changesNothing_andIsRetried() {
        idp.failFromNowOn();

        swapTo(document("https://api.two", "public.secrets"));

        assertThat(source.permissionsFor(PROJECT).tables().get(0).table()).isEqualTo("public.orders");
        assertThat(source.status().version()).isEqualTo(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unexpected_error");
    }

    @Test
    void aDocumentWithNoJwtSection_dropsTheVerifier() {
        swapTo("format: 1\nprojectId: demo\ntables: []\n");

        assertThat(source.tokenVerifier()).isEmpty();
    }
}
