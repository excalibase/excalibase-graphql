package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** A save whose write succeeds but whose reload refuses the stored text: the last good document stays served. */
class ReloadingMetadataSourceSaveTest {

    private ReloadingMetadataSource source;

    @AfterEach
    void close() {
        source.close();
    }

    private static String versionOf(String text) {
        return "v-" + text.hashCode();
    }

    @Test
    void aWriteTheReloadRefuses_isNotApplied_andTheLastGoodDocumentStaysServed() {
        AtomicReference<String> stored = new AtomicReference<>(GOOD_YAML);
        MetadataWriter storingSomethingElse = new MetadataWriter() {
            @Override
            public boolean writable() {
                return true;
            }

            @Override
            public String write(String text, String expectedVersion) {
                stored.set(BAD_UNKNOWN_KEY);
                return versionOf(BAD_UNKNOWN_KEY);
            }
        };
        source = new ReloadingMetadataSource("test", () -> new ReloadingMetadataSource.Raw(stored.get(),
                versionOf(stored.get())),
                new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT, false)::load,
                storingSomethingElse, Duration.ofHours(1), Duration.ofMillis(1), new SimpleMeterRegistry());
        source.start();

        SaveOutcome outcome = source.save(GOOD_YAML_TWO_RULES, versionOf(GOOD_YAML));

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.NotApplied.class, notApplied ->
                assertThat(notApplied.category()).isEqualTo("reload_refused"));
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
    }
}
