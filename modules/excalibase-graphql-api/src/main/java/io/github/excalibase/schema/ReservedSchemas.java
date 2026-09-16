package io.github.excalibase.schema;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Platform-owned schemas that must never be reflected into the GraphQL or REST API.
 * Defence in depth: the engine excludes them by name instead of relying on database
 * grants, so a single GRANT change cannot silently expose platform data.
 *
 * @param names the effective denylist, lower-cased, always a superset of {@link #BUILT_IN}
 */
public record ReservedSchemas(Set<String> names) {

    /**
     * {@code auth} holds end-user credentials (password hashes, reset tokens);
     * {@code excalibase} is reserved for control-plane bookkeeping tables.
     */
    public static final Set<String> BUILT_IN = Set.of("auth", "excalibase");

    public ReservedSchemas {
        Set<String> effective = new LinkedHashSet<>(BUILT_IN);
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    effective.add(normalize(name));
                }
            }
        }
        names = Set.copyOf(effective);
    }

    /**
     * Build the denylist from a comma-separated configuration value. Configuration can
     * only ADD entries — the built-ins are re-applied by the canonical constructor, so a
     * misconfiguration cannot expose a platform schema.
     */
    public static ReservedSchemas fromConfig(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return new ReservedSchemas(Set.of());
        }
        return new ReservedSchemas(new LinkedHashSet<>(Arrays.asList(commaSeparated.split(","))));
    }

    public boolean isReserved(String schemaName) {
        return schemaName != null && names.contains(normalize(schemaName));
    }

    /** Drop reserved schemas from a discovered list, keeping the original order. */
    public List<String> filter(List<String> discovered) {
        return discovered.stream().filter(schema -> !isReserved(schema)).toList();
    }

    private static String normalize(String schemaName) {
        return schemaName.trim().toLowerCase(Locale.ROOT);
    }
}
