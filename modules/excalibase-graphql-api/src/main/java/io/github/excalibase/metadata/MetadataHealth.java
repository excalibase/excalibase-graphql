package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionProvider;

import java.util.Map;
import java.util.Optional;

/**
 * What the actuators need to know about the metadata source without naming the permission model: nothing when
 * no metadata source is configured (the deployment behaves as before), else this replica's version and whether
 * it holds a good document.
 */
public final class MetadataHealth {

    private final MetadataSource source;

    public MetadataHealth(PermissionProvider permissions) {
        this.source = permissions instanceof MetadataSource metadata ? metadata : null;
    }

    /** False only while a configured metadata source has no good document, so every read is refused. */
    public boolean ready() {
        return source == null || source.current().isPresent();
    }

    public Optional<Map<String, Object>> report() {
        return Optional.ofNullable(source).map(metadata -> metadata.status().toMap());
    }
}
