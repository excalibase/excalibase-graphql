package io.github.excalibase.metadata;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The earlier versions of a document a source keeps, so a save can be looked at and undone. Newest first. */
public interface DocumentHistory {

    /**
     * One version that a save replaced.
     *
     * @param id   unique within the history and never reused, so a later save cannot make an id name another text
     * @param at   when the save that replaced this text happened
     * @param hash the version token this text had (SHA-256 of its UTF-8 bytes)
     * @param text the whole text, comments and layout included
     */
    record Entry(long id, Instant at, String hash, String text) {

        public int size() {
            return text.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /** The kept versions, newest first; empty when there are none or the history cannot be read. */
    List<Entry> list();

    Optional<Entry> get(long id);
}
