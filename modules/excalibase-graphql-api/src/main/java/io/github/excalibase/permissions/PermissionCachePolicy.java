package io.github.excalibase.permissions;

/**
 * How long a project's permission document is trusted (docs/features/permissions.md §8).
 *
 * @param ttlMillis           a fetched document is served without asking again for this long
 * @param maxStaleMillis      through a control-plane outage, the cached copy is served for this long
 *                            from the first failed refresh
 * @param retryIntervalMillis through an outage, the control plane is asked at most once per this long
 */
public record PermissionCachePolicy(long ttlMillis, long maxStaleMillis, long retryIntervalMillis) {

    public PermissionCachePolicy {
        requirePositive("app.security.permissions.max-stale-ms", maxStaleMillis);
        requirePositive("app.security.permissions.retry-interval-ms", retryIntervalMillis);
    }

    private static void requirePositive(String property, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be greater than 0, got " + value);
        }
    }
}
