package io.github.excalibase.metadata;

import java.util.List;

/** What a save did. Only {@link Saved} changed the source and the served document. */
public sealed interface SaveOutcome {

    /** Written and reloaded: {@code version} is the engine's counter now, {@code sourceVersion} the source's token. */
    record Saved(long version, String sourceVersion) implements SaveOutcome {
    }

    /** The source held another version than the writer started from. */
    record Conflict(String currentVersion) implements SaveOutcome {
    }

    /** The source cannot be saved to. */
    record ReadOnly() implements SaveOutcome {
    }

    /** The document does not load, so nothing was written. */
    record Invalid(List<String> errors) implements SaveOutcome {
    }

    /** The write failed and nothing was replaced; {@code category} is safe to show, the reason is in the log. */
    record Failed(String category) implements SaveOutcome {
    }

    /** Written, but the reload did not take it up; the last good document is still served. */
    record NotApplied(String category) implements SaveOutcome {
    }
}
