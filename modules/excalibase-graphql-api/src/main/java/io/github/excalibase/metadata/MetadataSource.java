package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.security.TokenVerifier;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where the engine's metadata document comes from. A source never answers with an empty document when it has
 * nothing valid: until a good load it refuses every read (see {@link PermissionProvider}), and a later bad
 * document or read error keeps the last good version.
 */
public interface MetadataSource extends PermissionProvider {

    /** The source's name as configured, e.g. {@code file}. */
    String name();

    /** The version being served, empty until the first good load. */
    Optional<Snapshot> current();

    /** The verifier the served document's JWT section describes; empty when there is no document or no section. */
    default Optional<TokenVerifier> tokenVerifier() {
        return current().map(Snapshot::verifier);
    }

    /** Whether a save through this source can succeed. */
    boolean writable();

    /**
     * Saves a new document if the source still holds {@code expectedVersion}, then reloads it through the ordinary
     * path. Never writes a document that does not load, and never replaces a version the writer did not start from.
     */
    default SaveOutcome save(String text, String expectedVersion) {
        return new SaveOutcome.ReadOnly();
    }

    /** The versions this source has replaced; empty for a source that keeps none. */
    default Optional<DocumentHistory> history() {
        return Optional.empty();
    }

    Status status();

    /**
     * One accepted version of the document.
     *
     * @param version       the local counter, up by one on every accepted swap; it feeds the engine's change detection
     * @param sourceVersion what the source calls this version (a content hash for the file source)
     * @param verifier      built from the document's {@code jwt:} section; null when it has none
     * @param text          the document as read, which the admin API shows
     */
    record Snapshot(PermissionSet permissions, long version, String sourceVersion, Instant loadedAt,
                    TokenVerifier verifier, String text) {
    }

    /**
     * What one replica reports about its metadata, so a replica stuck on an old version is visible. {@code version}
     * is 0 while nothing valid has been loaded; {@code lastRefusal} is a category only, the reason is in the log.
     */
    record Status(String source, long version, String sourceVersion, String lastRefusal, Instant lastRefusalAt) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("source", source);
            map.put("version", version);
            map.put("sourceVersion", sourceVersion);
            map.put("lastRefusal", lastRefusal);
            map.put("lastRefusalAt", lastRefusalAt == null ? null : lastRefusalAt.toString());
            return map;
        }
    }
}
