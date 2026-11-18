package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.JwtVerificationException;
import io.github.excalibase.security.TokenVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.excalibase.metadata.MetadataFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A new document with other JWT settings takes effect together with its rules, and a bad one changes nothing. */
class JwtReloadTest {

    private final AtomicReference<String> text = new AtomicReference<>();
    private JwksStub idp;
    private ReloadingMetadataSource source;
    private final List<ReloadingMetadataSource> opened = new ArrayList<>();

    @BeforeEach
    void start() {
        idp = new JwksStub();
        text.set(document("https://api.one", "public.orders"));
        source = started(false);
    }

    private ReloadingMetadataSource started(boolean envJwtConfigured) {
        ReloadingMetadataSource created = new ReloadingMetadataSource("test",
                () -> new ReloadingMetadataSource.Raw(text.get(), "v-" + text.get().hashCode()),
                new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT, envJwtConfigured)::load,
                () -> true, Duration.ofHours(1), Duration.ofMillis(1), new SimpleMeterRegistry());
        opened.add(created);
        created.start();
        return created;
    }

    @AfterEach
    void stop() {
        opened.forEach(ReloadingMetadataSource::close);
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
        TokenVerifier verifier = source.tokenVerifier().orElseThrow();
        String forOldAudience = tokenFor("https://api.one");

        assertThat(source.permissionsFor(PROJECT).tables().get(0).table()).isEqualTo("public.secrets");
        assertThat(verifier.verify(tokenFor("https://api.two"), PROJECT).role()).isEqualTo("user");
        assertThatThrownBy(() -> verifier.verify(forOldAudience, PROJECT))
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

    @Test
    void withTheEnginesOwnJwtSettings_aJwtSectionIsRefused_whetherTheProviderIsDownOrUp() {
        idp.failFromNowOn();
        ReloadingMetadataSource down = started(true);
        assertThat(down.current()).isEmpty();
        assertThat(down.status().lastRefusal()).as("refused as a document, not as a provider outage")
                .isEqualTo("invalid_document");

        try (JwksStub up = new JwksStub()) {
            text.set("format: 1\nprojectId: demo\njwt:\n  issuer: \"" + up.issuer() + "\"\n  jwksUrl: "
                    + up.jwksUrl() + "\n  audience: a\ntables: []\n");
            ReloadingMetadataSource reachable = started(true);

            assertThat(reachable.current()).isEmpty();
            assertThat(reachable.status().lastRefusal()).isEqualTo("invalid_document");
        }
    }

    @Test
    void aReloadThatAddsAJwtSection_whileTheEngineHasItsOwn_isRefused_andNothingChanges() {
        text.set("format: 1\nprojectId: demo\ntables: []\n");
        ReloadingMetadataSource running = started(true);

        text.set(document("https://api.two", "public.secrets"));
        running.refresh();
        running.refresh();

        assertThat(running.status().version()).isEqualTo(1);
        assertThat(running.tokenVerifier()).isEmpty();
        assertThat(running.status().lastRefusal()).isEqualTo("invalid_document");
    }

    @Test
    void aRulesOnlyEdit_swapsInWithTheProviderDown_andKeepsTheServedVerifier() {
        TokenVerifier before = source.tokenVerifier().orElseThrow();
        idp.failFromNowOn();

        swapTo(document("https://api.one", "public.secrets"));

        assertThat(source.permissionsFor(PROJECT).tables().get(0).table()).isEqualTo("public.secrets");
        assertThat(source.status().version()).isEqualTo(2);
        assertThat(source.tokenVerifier().orElseThrow()).isSameAs(before);
    }
}
