package io.github.excalibase.access;

import java.util.Map;
import java.util.Optional;

/**
 * What of one change a subscriber may see (docs/features/permissions.md §5, realtime). Built from the
 * access plan per subscription, so the rule is the one the query path applies.
 */
@FunctionalInterface
public interface ChangeFilter {

    /** Every change, whole: the all-access plan's filter. */
    ChangeFilter PASS_THROUGH = (eventType, data, probes) -> Optional.of(data);

    /**
     * @param data   the change payload: the row image, or {@code old}/{@code new} images for an UPDATE
     * @param probes asks the project's database about images memory cannot decide
     * @return the payload the subscriber may see, or empty when nothing of this change is theirs
     */
    Optional<Map<String, Object>> render(String eventType, Map<String, Object> data, ProbeRunner probes);
}
