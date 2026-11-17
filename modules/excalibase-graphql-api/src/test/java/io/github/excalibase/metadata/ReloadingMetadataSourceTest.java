package io.github.excalibase.metadata;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.UnknownProjectException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
        ReloadingMetadataSource.Loader loader = () -> {
            String current = text.get();
            if (current == null) {
                throw new IOException("the file is gone");
            }
            return new ReloadingMetadataSource.Raw(current, "v-" + current.hashCode());
        };
        ReloadingMetadataSource source = new ReloadingMetadataSource("test", loader,
                new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT)::parse, () -> true, interval, meters);
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
        AtomicInteger reads = new AtomicInteger();
        ReloadingMetadataSource.Loader loader = () -> {
            String content = reads.getAndIncrement() == 0 ? "format: 1\nprojectId: demo\ntables: []\n" : GOOD_YAML;
            return new ReloadingMetadataSource.Raw(content, "v-" + content.hashCode());
        };
        ReloadingMetadataSource source = new ReloadingMetadataSource("test", loader,
                new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT)::parse, () -> true,
                Duration.ofHours(1), meters);
        opened.add(source);

        source.start();

        assertThat(reads.get()).as("start reads twice before it believes anything").isEqualTo(2);
        assertThat(source.current()).isEmpty();
        source.refresh();
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    /** Whatever goes wrong while reading a document is a refusal, never a crash and never a retry-free skip. */
    @Test
    void anUnexpectedFailureWhileParsing_isARefusal_countedAndRetried() {
        ReloadingMetadataSource.Reader exploding = (document, version) -> {
            throw new IllegalStateException("boom");
        };
        ReloadingMetadataSource source = new ReloadingMetadataSource("test",
                () -> new ReloadingMetadataSource.Raw(GOOD_YAML, "same"), exploding, () -> true, Duration.ofHours(1), meters);
        opened.add(source);

        source.start();
        source.refresh();
        source.refresh();

        assertThat(source.current()).isEmpty();
        assertThat(source.status().lastRefusal()).isEqualTo("unexpected_error");
        assertThat(meters.get("excalibase.metadata.refused").tag("source", "test").counter().count())
                .as("retried on every poll, because the content was never judged").isGreaterThanOrEqualTo(2.0);
    }

    /** bad, C, bad, C: C was never seen on two polls in a row, so it must not be swapped in. */
    @Test
    void aDocumentSeenTwiceButNotBackToBack_isNotSwappedIn() {
        ReloadingMetadataSource source = started();
        text.set(BAD_RULE);
        settle(source);
        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");

        text.set(GOOD_YAML_TWO_RULES);
        source.refresh();
        text.set(BAD_RULE);
        source.refresh();
        text.set(GOOD_YAML_TWO_RULES);
        source.refresh();

        assertThat(source.status().version()).isEqualTo(1);
        source.refresh();
        assertThat(source.status().version()).isEqualTo(2);
    }

    @Test
    void aChangeThatNeverSettles_isWarnedAboutOnce() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReloadingMetadataSource.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReloadingMetadataSource source = started();
            for (int poll = 0; poll < ReloadingMetadataSource.UNSETTLED_WARNING_POLLS + 4; poll++) {
                text.set(GOOD_YAML + "# " + poll + "\n");
                source.refresh();
            }

            assertThat(source.status().version()).isEqualTo(1);
            assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("metadata_change_not_settling"))).hasSize(1);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aChangeThatSettles_isNotWarnedAbout() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReloadingMetadataSource.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReloadingMetadataSource source = started();
            text.set(GOOD_YAML_TWO_RULES);
            settle(source);

            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage()
                    .contains("metadata_change_not_settling"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
