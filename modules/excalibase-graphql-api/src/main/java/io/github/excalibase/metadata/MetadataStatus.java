package io.github.excalibase.metadata;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one replica reports about its metadata: the version it serves and the last refusal, if any, so a replica
 * stuck on an old version is visible. {@code version} is 0 while nothing valid has been loaded.
 */
public record MetadataStatus(String source, long version, String sourceVersion, String lastRefusal,
                             Instant lastRefusalAt) {

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
