package io.github.excalibase.observability;

import java.util.Locale;

/**
 * How much per-request monitoring the engine does ({@code excalibase.observability.level}).
 * {@link #FULL} keeps every observation, span and metric; {@link #LITE} swaps the HTTP server and
 * Spring Security observations for a plain request timer with the same tags, which costs far less CPU;
 * {@link #OFF} drops every request observation.
 */
public enum ObservabilityLevel {
    FULL, LITE, OFF;

    public static final String PROPERTY = "excalibase.observability.level";

    /** Unset or blank means {@link #FULL}; anything that is not a level fails rather than guessing. */
    public static ObservabilityLevel parse(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        for (ObservabilityLevel level : values()) {
            if (level.name().equals(normalized)) {
                return level;
            }
        }
        throw new IllegalStateException(PROPERTY + " must be one of full, lite, off but was '" + value.strip() + "'");
    }
}
