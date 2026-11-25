package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlMetadataSourceTest extends UrlSourceFixture {

    @Test
    void a200_isServed_andIsNamedAfterTheSource() {
        ReloadingMetadataSource source = open();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.name()).isEqualTo("url");
        assertThat(source.writable()).isFalse();
        assertThat(source.status().sourceVersion()).startsWith("sha256:");
    }

    @Test
    void theVersionIsTheBodyHash_andNoPollAsksForANotModified() {
        etag = "\"v1\"";
        ReloadingMetadataSource source = open();

        settle(source);

        assertThat(ifNoneMatch).containsOnly("null");
        assertThat(source.status().sourceVersion()).startsWith("sha256:");
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aSameEtagWithAChangedBody_isStillApplied() {
        etag = "\"same\"";
        ReloadingMetadataSource source = open();

        body = GOOD_YAML_TWO_RULES;
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
    }

    @Test
    void aNewEtagOnEveryPoll_forTheSameBody_doesNotStarveTheSource() {
        AtomicInteger counter = new AtomicInteger();
        server.createContext("/churn", exchange -> reply(exchange, 200, GOOD_YAML, "\"e" + counter.incrementAndGet() + "\""));

        ReloadingMetadataSource source = open(settings("/churn", "", "", Duration.ofSeconds(5)));

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aTruncatedBodyFollowedByA304_isNotSettled() {
        ReloadingMetadataSource source = open();

        body = GOOD_YAML_TWO_RULES;
        etag = "\"t\"";
        source.refresh();
        status = 304;
        source.refresh();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aNotModifiedNobodyAskedFor_isRefused_andServesNothingBeforeTheFirstLoad() {
        status = 304;

        ReloadingMetadataSource source = open();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void aNewEtag_isApplied_afterTheTwoReadsRule() {
        etag = "\"v1\"";
        ReloadingMetadataSource source = open();

        body = GOOD_YAML_TWO_RULES;
        etag = "\"v2\"";
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
        assertThat(source.status().version()).isEqualTo(2);
    }

    @Test
    void withoutAnEtag_aChangedBodyIsApplied_andAnUnchangedBodyIsNot() {
        ReloadingMetadataSource source = open();
        settle(source);
        assertThat(source.status().version()).isEqualTo(1);

        body = GOOD_YAML_TWO_RULES;
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
        assertThat(source.status().version()).isEqualTo(2);
        assertThat(ifNoneMatch).containsOnly("null");
    }

    @Test
    void aServerError_keepsTheLastGoodVersion() {
        ReloadingMetadataSource source = open();

        status = 503;
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void withNothingLoaded_everyReadIsRefused_untilTheServerRecovers() {
        status = 500;
        ReloadingMetadataSource source = open();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);

        status = 200;
        settle(source);
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void aSlowServer_timesOut_andKeepsTheLastGoodVersion() {
        ReloadingMetadataSource source = open(settings("/permissions.yaml", "", "", Duration.ofMillis(300)));

        delayMs = 1500;
        source.refresh();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void aBodyThatStalls_timesOutToo() {
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("format: 1\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            sleep(3000);
            exchange.close();
        });

        ReloadingMetadataSource source = open(settings("/stall", "", "", Duration.ofMillis(400)));

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void anOversizedBody_isRefused_withAKnownLength() {
        ReloadingMetadataSource source = open();

        body = GOOD_YAML + "# " + "x".repeat(MetadataDocumentParser.MAX_BYTES) + "\n";
        settle(source);

        assertThat(source.status().version()).isEqualTo(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void anOversizedBody_isCutOffWhileStreaming_withNoLength() {
        server.createContext("/endless", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = "# xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n"
                    .repeat(1024).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int sent = 0; sent < 64; sent++) {
                    out.write(chunk);
                }
            } catch (IOException _) {
                // the client hung up once it had read more than the limit, as it should
            }
        });

        ReloadingMetadataSource source = open(settings("/endless", "", "", Duration.ofSeconds(5)));

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void aBadDocument_keepsTheLastGoodVersion() {
        ReloadingMetadataSource source = open();

        body = BAD_RULE;
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
    }

    @Test
    void aRedirectToAnotherHost_isRefused_andTheTargetIsNeverContacted() {
        ReloadingMetadataSource source = open(settings("/away", TOKEN, "", Duration.ofSeconds(5)));

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        assertThat(elsewhereHits).hasValue(0);
    }

    @Test
    void aRedirectWithinTheSameHost_isFollowed() {
        ReloadingMetadataSource source = open(settings("/moved", "", "", Duration.ofSeconds(5)));

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void plainHttpToAnOutsideHost_isRefusedAtStart() {
        UrlSettings insecure = new UrlSettings("http://config.example.com/p.yaml", "", "", false,
                Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofSeconds(1));

        ReservedSchemas reserved = ReservedSchemas.fromConfig("");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> UrlMetadataSource.create(insecure, reserved, PROJECT, registry, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-insecure");
    }
}

