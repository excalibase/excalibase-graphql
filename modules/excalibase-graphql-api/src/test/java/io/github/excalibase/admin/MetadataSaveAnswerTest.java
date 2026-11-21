package io.github.excalibase.admin;

import io.github.excalibase.metadata.SaveOutcome;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What each outcome of a save is answered with, and how an If-Match value names a version. */
class MetadataSaveAnswerTest {

    private static HttpResponse<Map<String, Object>> answer(SaveOutcome outcome) {
        return MetadataSave.answer(outcome);
    }

    @Test
    void aSavedDocument_is200_withBothVersions() {
        HttpResponse<Map<String, Object>> response = answer(new SaveOutcome.Saved(3, "abc"));

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.body()).containsEntry("version", 3L).containsEntry("sourceVersion", "abc");
    }

    @Test
    void aConflict_is409_withTheCurrentVersion() {
        HttpResponse<Map<String, Object>> response = answer(new SaveOutcome.Conflict("now"));

        assertThat(response.code()).isEqualTo(409);
        assertThat(response.body()).containsEntry("error", "version_conflict").containsEntry("currentVersion", "now");
    }

    @Test
    void aFailedWrite_is502_andAReloadThatRefusedIt_is500() {
        assertThat(answer(new SaveOutcome.Failed("write_failed")).code()).isEqualTo(502);
        HttpResponse<Map<String, Object>> notApplied = answer(new SaveOutcome.NotApplied("reload_refused"));

        assertThat(notApplied.code()).isEqualTo(500);
        assertThat(notApplied.body()).containsEntry("error", "reload_refused");
    }

    @Test
    void anInvalidDocument_is422_withItsErrors_andAReadOnlySource_is405() {
        HttpResponse<Map<String, Object>> invalid = answer(new SaveOutcome.Invalid(List.of("format: must be 1")));

        assertThat(invalid.code()).isEqualTo(422);
        assertThat(invalid.body()).containsEntry("errors", List.of("format: must be 1"));
        assertThat(answer(new SaveOutcome.ReadOnly()).code()).isEqualTo(405);
    }

    @Test
    void anIfMatchValue_namesAVersionByItsStrongTag_andAWeakTagNamesNone() {
        assertThat(MetadataSave.version("\"abc\"")).isEqualTo("abc");
        assertThat(MetadataSave.version("abc")).isEqualTo("abc");
        assertThat(MetadataSave.version("W/\"abc\"")).isNotEqualTo("abc");
    }

    @Test
    void aSaveSaysWhenTheHistoryMissedIt_andSaysNothingWhenThereIsNoHistory() {
        assertThat(answer(new SaveOutcome.Saved(3, "abc", false)).body()).containsEntry("historyRecorded", false);
        assertThat(answer(new SaveOutcome.Saved(3, "abc", true)).body()).containsEntry("historyRecorded", true);
        assertThat(answer(new SaveOutcome.Saved(3, "abc")).body()).doesNotContainKey("historyRecorded");
    }
}
