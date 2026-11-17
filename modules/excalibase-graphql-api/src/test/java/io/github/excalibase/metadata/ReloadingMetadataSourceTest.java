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
        source.refresh();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
        assertThat(source.permissionsFor(PROJECT).version()).isEqualTo(2);
        assertThat(source.status().version()).isEqualTo(2);
        assertThat(announced).containsExactly("demo@2");
    }

    @Test
    void anUnchangedDocument_doesNotSwap() {
        ReloadingMetadataSource source = started();

        source.refresh();
        source.refresh();

        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aBadChange_keepsTheLastGoodVersion_andSaysWhy() {
        ReloadingMetadataSource source = started();

        text.set(BAD_RULE);
        source.refresh();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().version()).isEqualTo(1);
        assertThat(source.status().lastRefusal()).contains("tables[0].select.filter");
        assertThat(meters.get("excalibase.metadata.refused").tag("source", "test").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aBadDocument_isCountedOncePerDistinctContent_notOncePerPoll() {
        ReloadingMetadataSource source = started();

        text.set(BAD_RULE);
        source.refresh();
        source.refresh();
        source.refresh();

        assertThat(meters.get("excalibase.metadata.refused").tag("source", "test").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aReadError_keepsTheLastGoodVersion() {
        ReloadingMetadataSource source = started();

        text.set(null);
        source.refresh();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).contains("the file is gone");
    }

    @Test
    void aFileThatComesBackUnchanged_clearsTheRefusalWithoutASwap() {
        ReloadingMetadataSource source = started();
        text.set(null);
        source.refresh();

        text.set(GOOD_YAML);
        source.refresh();

        assertThat(source.status().lastRefusal()).isNull();
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aFixAfterABadDocument_swapsInTheNewVersion() {
        ReloadingMetadataSource source = started();
        text.set(BAD_RULE);
        source.refresh();

        text.set(GOOD_YAML_TWO_RULES);
        source.refresh();

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
        source.refresh();

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
        source.refresh();

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
        source.refresh();

        assertThat(source.status().version()).isEqualTo(2);
    }
}
