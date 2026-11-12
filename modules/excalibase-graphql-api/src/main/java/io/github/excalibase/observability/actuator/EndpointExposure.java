package io.github.excalibase.observability.actuator;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which actuator endpoints are served ({@code management.endpoints.web.exposure.include} and {@code .exclude}):
 * a comma separated list of ids, {@code *} for all, with the exclude list winning.
 */
public final class EndpointExposure {

    private static final String ALL = "*";

    private final Set<String> included;
    private final Set<String> excluded;

    private EndpointExposure(Set<String> included, Set<String> excluded) {
        this.included = included;
        this.excluded = excluded;
    }

    public static EndpointExposure of(String include, String exclude) {
        return new EndpointExposure(ids(include), ids(exclude));
    }

    public boolean exposes(String endpointId) {
        String id = endpointId.toLowerCase(Locale.ROOT);
        if (excluded.contains(ALL) || excluded.contains(id)) {
            return false;
        }
        return included.contains(ALL) || included.contains(id);
    }

    private static Set<String> ids(String list) {
        if (list == null) {
            return Set.of();
        }
        return Arrays.stream(list.split(",")).map(String::strip).filter(id -> !id.isEmpty())
                .map(id -> id.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
