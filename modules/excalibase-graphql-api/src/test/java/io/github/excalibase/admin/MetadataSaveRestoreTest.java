package io.github.excalibase.admin;

import io.github.excalibase.metadata.AdminMetadataView;
import io.github.excalibase.metadata.DocumentHistory;
import io.github.excalibase.metadata.MetadataSource;
import io.github.excalibase.metadata.SaveOutcome;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.schema.ReservedSchemas;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A restore is validated by the admin layer on its own, whatever the source would accept: the source here is lenient
 * on purpose (it takes any text), so only {@code DocumentValidation} stands between a stale entry and the file.
 */
class MetadataSaveRestoreTest {

    private static final AdminConfig.ValidationSettings RULES =
            new AdminConfig.ValidationSettings(ReservedSchemas.fromConfig(""), "proj-admin", false);
    private static final String STALE = AdminTestEngine.DOCUMENT.replace("proj-admin", "someone-else");

    private final AtomicInteger saves = new AtomicInteger();

    private AdminMetadataView lenientSource(String entryText) {
        DocumentHistory history = new DocumentHistory() {
            @Override
            public List<Entry> list() {
                return List.of(new Entry(7, Instant.EPOCH, "h", entryText));
            }

            @Override
            public Optional<Entry> get(long id) {
                return id == 7 ? Optional.of(list().getFirst()) : Optional.empty();
            }
        };
        return new AdminMetadataView(new MetadataSource() {
            @Override
            public String name() {
                return "lenient";
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
                return new SaveOutcome.Saved(2, "v2");
            }

            @Override
            public Optional<DocumentHistory> history() {
                return Optional.of(history);
            }

            @Override
            public Status status() {
                return new Status("lenient", 1, "v1", null, null);
            }

            @Override
            public void evict(String projectId) {
            }

            @Override
            public PermissionSet permissionsFor(String projectId) {
                throw new UnsupportedOperationException();
            }
        });
    }

    @Test
    void anEntryForAnotherProject_isRefusedByTheAdminCheck_andNeverReachesTheSource() {
        HttpResponse<Map<String, Object>> answer = MetadataSave.restore(lenientSource(STALE), RULES, "\"v1\"", "7");

        assertThat(answer.code()).isEqualTo(422);
        assertThat(saves).hasValue(0);
    }

    @Test
    void aValidEntry_goesThroughToTheSource() {
        HttpResponse<Map<String, Object>> answer =
                MetadataSave.restore(lenientSource(AdminTestEngine.DOCUMENT), RULES, "\"v1\"", "7");

        assertThat(answer.code()).isEqualTo(200);
        assertThat(saves).hasValue(1);
    }
}
