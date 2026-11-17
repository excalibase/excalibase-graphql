package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;

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

    /** Whether a save through this source can succeed. */
    boolean writable();

    Status status();

    /**
     * One accepted version of the document.
     *
     * @param version       the local counter, up by one on every accepted swap; it feeds the engine's change detection
     * @param sourceVersion what the source calls this version (a content hash for the file source)
     */
    record Snapshot(PermissionSet permissions, long version, String sourceVersion, Instant loadedAt) {
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
