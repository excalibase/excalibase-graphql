package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.UnknownProjectException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ReloadingMetadataSourceTest {

    /** What the loader answers next: text, or null for "cannot be read". */
    private final AtomicReference<String> text = new AtomicReference<>(GOOD_YAML);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<ReloadingMetadataSource> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(ReloadingMetadataSource::close);
    }

    private ReloadingMetadataSource source(Duration interval) {
        MetadataLoader loader = () -> {
            String current = text.get();
            if (current == null) {
                throw new IOException("the file is gone");
            }
            return new RawMetadata(current, "v-" + current.hashCode());
        };
        ReloadingMetadataSource source = new ReloadingMetadataSource("test", loader,
                new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT), () -> true, interval, meters);
        opened.add(source);
        return source;
    }

    /** A changed document is judged only once seen unchanged on two polls in a row. */
    private static void settle(ReloadingMetadataSource source) {
        source.refresh();
        source.refresh();
    }

    private ReloadingMetadataSource started() {
        ReloadingMetadataSource source = source(Duration.ofHours(1));
        source.start();
        return source;
    }

    @Test
    void aGoodDocument_isServedAtStartup() {
        ReloadingMetadataSource source = started();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().version()).isEqualTo(1);
        assertThat(source.status().source()).isEqualTo("test");
        assertThat(source.status().lastRefusal()).isNull();
        assertThat(source.configured()).isTrue();
    }

    @Test
    void aGoodChange_swapsAndIsAnnounced() {
        ReloadingMetadataSource source = started();
        List<String> announced = new ArrayList<>();
        source.onChange((project, version) -> announced.add(project + "@" + version));

        text.set(GOOD_YAML_TWO_RULES);
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
        assertThat(source.permissionsFor(PROJECT).version()).isEqualTo(2);
        assertThat(source.status().version()).isEqualTo(2);
        assertThat(announced).containsExactly("demo@2");
    }

    @Test
    void anUnchangedDocument_doesNotSwap() {
        ReloadingMetadataSource source = started();

        settle(source);
        settle(source);

        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aBadChange_keepsTheLastGoodVersion_andSaysWhy() {
        ReloadingMetadataSource source = started();

        text.set(BAD_RULE);
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().version()).isEqualTo(1);
        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
        assertThat(meters.get("excalibase.metadata.refused").tag("source", "test").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aBadDocument_isCountedOncePerDistinctContent_notOncePerPoll() {
        ReloadingMetadataSource source = started();

        text.set(BAD_RULE);
        settle(source);
        settle(source);
        settle(source);

        assertThat(meters.get("excalibase.metadata.refused").tag("source", "test").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aReadError_keepsTheLastGoodVersion() {
        ReloadingMetadataSource source = started();

        text.set(null);
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void aFileThatComesBackUnchanged_clearsTheRefusalWithoutASwap() {
        ReloadingMetadataSource source = started();
        text.set(null);
        settle(source);

        text.set(GOOD_YAML);
        settle(source);

        assertThat(source.status().lastRefusal()).isNull();
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aFixAfterABadDocument_swapsInTheNewVersion() {
        ReloadingMetadataSource source = started();
        text.set(BAD_RULE);
        settle(source);

        text.set(GOOD_YAML_TWO_RULES);
        settle(source);

        assertThat(source.status().version()).isEqualTo(2);
        assertThat(source.status().lastRefusal()).isNull();
    }

    @Test
    void aBadDocumentAtStartup_refusesEverything_andRecoversWhenFixed() {
        text.set(BAD_RULE);
        ReloadingMetadataSource source = started();

        assertThat(source.current()).isEmpty();
        assertThat(source.status().version()).isZero();
        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);

        text.set(GOOD_YAML);
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void anUnreadableDocumentAtStartup_refusesEverything() {
        text.set(null);
        ReloadingMetadataSource source = started();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void beforeStart_everythingIsRefused() {
        ReloadingMetadataSource source = source(Duration.ofHours(1));

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void anotherProject_isUnknown() {
        ReloadingMetadataSource source = started();

        assertThatThrownBy(() -> source.permissionsFor("someone-else")).isInstanceOf(UnknownProjectException.class);
    }

    @Test
    void theVersionIsAGauge() {
        ReloadingMetadataSource source = started();
        text.set(GOOD_YAML_TWO_RULES);
        settle(source);

        assertThat(meters.get("excalibase.metadata.version").tag("source", "test").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void polling_picksUpAChangeWithinABound() {
        ReloadingMetadataSource source = source(Duration.ofMillis(30));
        source.start();

        text.set(GOOD_YAML_TWO_RULES);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2));
    }

    @Test
    void aListenerThatFails_doesNotStopTheSwap() {
        ReloadingMetadataSource source = started();
        source.onChange((project, version) -> {
            throw new IllegalStateException("boom");
        });

        text.set(GOOD_YAML_TWO_RULES);
        settle(source);

        assertThat(source.status().version()).isEqualTo(2);
    }

    @Test
    void aStatusReportsOnlyACategory_neverTheDocumentsOwnWords() {
        ReloadingMetadataSource source = started();

        text.set(BAD_UNKNOWN_KEY);
        settle(source);

        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document").doesNotContain("surprise");
        assertThat(source.status().lastRefusalAt()).isNotNull();
    }

    /** A writer that has not finished must not be believed: a cut-off line can read as a wider rule. */
    @Test
    void aHalfWrittenDocument_isNeverSwappedIn() {
        ReloadingMetadataSource source = started();
        List<Long> announced = new ArrayList<>();
        source.onChange((project, version) -> announced.add(version));
        String partial = GOOD_YAML_TWO_RULES.substring(0, GOOD_YAML_TWO_RULES.indexOf("role: admin") + 4);

        text.set(partial);
        source.refresh();
        assertThat(source.status().version()).isEqualTo(1);

        text.set(GOOD_YAML_TWO_RULES);
        source.refresh();
        assertThat(source.status().version()).as("the complete text has been seen once").isEqualTo(1);
        assertThat(announced).isEmpty();

        source.refresh();
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
        assertThat(source.permissionsFor(PROJECT).tables().get(1).role()).isEqualTo("admin");
    }

    @Test
    void aPartialReadThatGoesBackToWhatWasServed_changesNothing() {
        ReloadingMetadataSource source = started();

        text.set(GOOD_YAML_TWO_RULES.substring(0, GOOD_YAML_TWO_RULES.indexOf("role: admin") + 4));
        source.refresh();
        text.set(GOOD_YAML);
        source.refresh();

        assertThat(source.status().version()).isEqualTo(1);
        assertThat(source.status().lastRefusal()).isNull();
        assertThat(meters.find("excalibase.metadata.refused").counter()).isNull();
    }

    @Test
    void aStartupReadThatIsStillChanging_isNotTrusted() {
        text.set(GOOD_YAML);
        AtomicInteger reads = new AtomicInteger();
        MetadataLoader loader = () -> {
            String content = reads.getAndIncrement() == 0 ? "format: 1\nprojectId: demo\ntables: []\n" : GOOD_YAML;
            return new RawMetadata(content, "v-" + content.hashCode());
        };
        ReloadingMetadataSource source = new ReloadingMetadataSource("test", loader,
                new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT), () -> true,
                Duration.ofHours(1), meters);
        opened.add(source);

        source.start();

        assertThat(source.current()).isEmpty();
        source.refresh();
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    /** Whatever goes wrong while reading a document is a refusal, never a crash and never a retry-free skip. */
    @Test
    void anUnexpectedFailureWhileParsing_isARefusal_countedAndRetried() {
        MetadataDocumentParser exploding = new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT) {
            @Override
            public MetadataDocument parse(String document, long version) {
                throw new IllegalStateException("boom");
            }
        };
        ReloadingMetadataSource source = new ReloadingMetadataSource("test",
                () -> new RawMetadata(GOOD_YAML, "same"), exploding, () -> true, Duration.ofHours(1), meters);
        opened.add(source);

        source.start();
        source.refresh();
        source.refresh();

        assertThat(source.current()).isEmpty();
        assertThat(source.status().lastRefusal()).isEqualTo("unexpected_error");
        assertThat(meters.get("excalibase.metadata.refused").tag("source", "test").counter().count())
                .as("retried on every poll, because the content was never judged").isGreaterThanOrEqualTo(2.0);
    }
}
