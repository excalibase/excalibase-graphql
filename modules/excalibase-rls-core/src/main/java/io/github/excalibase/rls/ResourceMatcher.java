package io.github.excalibase.rls;

/**
 * Matches a policy's {@code resource} against the engine's table key. Policies
 * authored in provisioning carry a bare table name ("orders"), while the engine
 * keys tables schema-qualified ("public.orders"). A bare resource matches the
 * table-name suffix of a qualified key; exact matches keep working unchanged.
 */
public final class ResourceMatcher {

    private ResourceMatcher() {
    }

    public static boolean matches(String policyResource, String tableKey) {
        if (policyResource == null || tableKey == null) {
            return false;
        }
        if (policyResource.equals(tableKey)) {
            return true;
        }
        int dot = tableKey.lastIndexOf('.');
        return dot >= 0 && policyResource.equals(tableKey.substring(dot + 1));
    }
}
