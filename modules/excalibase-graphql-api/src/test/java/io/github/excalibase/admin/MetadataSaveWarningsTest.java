package io.github.excalibase.admin;

import io.github.excalibase.access.RuleProblem;
import io.github.excalibase.metadata.AdminMetadataView;
import io.github.excalibase.metadata.DocumentHistory;
import io.github.excalibase.metadata.MetadataSource;
import io.github.excalibase.metadata.SaveOutcome;
import io.github.excalibase.metadata.SchemaCheck;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.ReservedSchemas;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** A rule the database does not fit is told to the caller beside a successful save; it never blocks one. */
class MetadataSaveWarningsTest {

    private static final AdminConfig.ValidationSettings RULES =
            new AdminConfig.ValidationSettings(ReservedSchemas.fromConfig(""), "proj-admin", true);
    private static final byte[] DOCUMENT = AdminTestEngine.DOCUMENT.getBytes(StandardCharsets.UTF_8);

    private final AtomicInteger saves = new AtomicInteger();
    private SaveOutcome outcome = new SaveOutcome.Saved(2, "v2");

    private AdminMetadataView source() {
        return new AdminMetadataView(new MetadataSource() {
            @Override
            public String name() {
                return "file";
            }

            @Override
            public Optional<Snapshot> current() {
                return Optional.of(new Snapshot(null, 1, "v1", Instant.EPOCH, null, AdminTestEngine.DOCUMENT));
            }

            @Override
            public boolean writable() {
                return true;
            }

            @Override
            public SaveOutcome save(String text, String expectedVersion) {
                saves.incrementAndGet();
                return outcome;
            }

            @Override
            public Status status() {
                return new Status("file", 1, "v1", null, null);
            }

            @Override
            public void evict(String projectId) {
                // nothing is cached here
            }

            @Override
            public PermissionSet permissionsFor(String projectId) {
                throw new UnsupportedOperationException();
            }
        });
    }

    private static final SchemaCheck ONE_PROBLEM = document -> List.of(
            new RuleProblem("user", "public.orders", "table", "unknown_table", "no such table"));

    @Test
    void aSaveWithAProblemRuleStillSavesAndCarriesTheWarning() {
        HttpResponse<Map<String, Object>> answer = MetadataSave.respond(source(), RULES, "\"v1\"", DOCUMENT, ONE_PROBLEM);

        assertThat(answer.code()).isEqualTo(200);
        assertThat(saves).hasValue(1);
        assertThat(answer.body()).containsEntry("version", 2L).containsKey("warnings");
        assertThat((List<?>) answer.body().get("warnings")).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("savesThatDidNotHappen")
    void aSaveThatFailedNeverBecomesASuccessBecauseItHadWarnings(SaveOutcome failed, int status) {
        outcome = failed;

        HttpResponse<Map<String, Object>> answer = MetadataSave.respond(source(), RULES, "\"v1\"", DOCUMENT, ONE_PROBLEM);

        assertThat(answer.code()).isEqualTo(status);
        assertThat(answer.body()).doesNotContainKey("warnings");
    }

    static Stream<Arguments> savesThatDidNotHappen() {
        return Stream.of(
                Arguments.of(new SaveOutcome.Conflict("v9"), 409),
                Arguments.of(new SaveOutcome.Failed("write_failed"), 502),
                Arguments.of(new SaveOutcome.NotApplied("reload_refused"), 500),
                Arguments.of(new SaveOutcome.Invalid(List.of("no")), 422));
    }

    @Test
    void aSaveThatFitsHasNoWarningsKey() {
        HttpResponse<Map<String, Object>> answer = MetadataSave.respond(source(), RULES, "\"v1\"", DOCUMENT,
                document -> List.of());

        assertThat(answer.code()).isEqualTo(200);
        assertThat(answer.body()).doesNotContainKey("warnings");
    }

    @Test
    void aSchemaCheckThatThrowsNeverBlocksTheSave() {
        HttpResponse<Map<String, Object>> answer = MetadataSave.respond(source(), RULES, "\"v1\"", DOCUMENT, document -> {
            throw new IllegalStateException("schema is reloading");
        });

        assertThat(answer.code()).isEqualTo(200);
        assertThat(saves).hasValue(1);
    }

    @Test
    void withoutASchemaTheSaveIsTheSameAsBefore() {
        HttpResponse<Map<String, Object>> answer = MetadataSave.respond(source(), RULES, "\"v1\"", DOCUMENT);

        assertThat(answer.code()).isEqualTo(200);
        assertThat(answer.body()).doesNotContainKey("warnings");
    }

    @Test
    void aRestoreCarriesTheWarningsToo() {
        DocumentHistory history = new DocumentHistory() {
            @Override
            public List<Entry> list() {
                return List.of(new Entry(7, Instant.EPOCH, "h", AdminTestEngine.DOCUMENT));
            }

            @Override
            public Optional<Entry> get(long id) {
                return id == 7 ? Optional.of(list().getFirst()) : Optional.empty();
            }
        };
        AdminMetadataView view = new AdminMetadataView(sourceWith(history));

        HttpResponse<Map<String, Object>> answer = MetadataSave.restore(view, RULES, "\"v1\"", "7", ONE_PROBLEM);

        assertThat(answer.code()).isEqualTo(200);
        assertThat(answer.body()).containsKey("warnings");
    }

    private MetadataSource sourceWith(DocumentHistory history) {
        return new MetadataSource() {
            @Override
            public String name() {
                return "file";
            }

            @Override
            public Optional<Snapshot> current() {
                return Optional.of(new Snapshot(null, 1, "v1", Instant.EPOCH, null, AdminTestEngine.DOCUMENT));
            }

            @Override
            public boolean writable() {
                return true;
            }

            @Override
            public SaveOutcome save(String text, String expectedVersion) {
                return new SaveOutcome.Saved(2, "v2");
            }

            @Override
            public Optional<DocumentHistory> history() {
                return Optional.of(history);
            }

            @Override
            public Status status() {
                return new Status("file", 1, "v1", null, null);
            }

            @Override
            public void evict(String projectId) {
                // nothing is cached here
            }

            @Override
            public PermissionSet permissionsFor(String projectId) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
