package io.github.excalibase.metadata;

import io.github.excalibase.admin.AdminSettings;
import io.github.excalibase.permissions.PermissionProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the admin API reports about the metadata source, so it does not have to name the permission source:
 * the served document and this replica's state. Empty when the deployment has no metadata source.
 */
@Singleton
@Requires(property = "excalibase.console.enabled", pattern = AdminSettings.ON_PATTERN)
public final class AdminMetadataView {

    private final MetadataSource source;

    AdminMetadataView(@Nullable PermissionProvider permissions) {
        this.source = permissions instanceof MetadataSource metadata ? metadata : null;
    }

    /**
     * The document as served and its version, whether the source can be saved to right now, and whether it keeps a
     * version history.
     * The document is null while no valid one has been loaded.
     */
    public Optional<Map<String, Object>> document() {
        if (source == null) {
            return Optional.empty();
        }
        MetadataSource.Status status = source.status();
        MetadataSource.Snapshot served = source.current().orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source.name());
        body.put("version", status.version());
        body.put("sourceVersion", status.sourceVersion());
        body.put("document", served == null ? null : served.text());
        body.put("writable", source.writable());
        body.put("history", List.of());
        body.put("historyAvailable", history().isPresent());
        body.put("lastRefusal", status.lastRefusal());
        body.put("lastRefusalAt", status.lastRefusalAt() == null ? null : status.lastRefusalAt().toString());
        return Optional.of(body);
    }

    /** The versions the source has replaced; empty when it keeps none (only the file source does). */
    public Optional<DocumentHistory> history() {
        return source == null ? Optional.empty() : source.history();
    }

    /** Saves through the source: conditional on the version, validated, then reloaded. */
    public SaveOutcome save(String text, String expectedVersion) {
        return source == null ? new SaveOutcome.ReadOnly() : source.save(text, expectedVersion);
    }

    /** Whether there is a source and it can be saved to right now. */
    public boolean writable() {
        return source != null && source.writable();
    }

    /** This replica: its version, and whether it holds a good document. */
    public Map<String, Object> replica() {
        Map<String, Object> replica = new LinkedHashMap<>();
        replica.put("self", true);
        if (source == null) {
            replica.put("source", null);
            replica.put("version", 0);
            replica.put("ready", true);
        } else {
            replica.putAll(source.status().toMap());
            replica.put("ready", source.current().isPresent());
        }
        return replica;
    }
}
