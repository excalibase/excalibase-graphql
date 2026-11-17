package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionProvider;

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
    Optional<MetadataSnapshot> current();

    /** Whether a save through this source can succeed. */
    boolean writable();

    MetadataStatus status();
}
