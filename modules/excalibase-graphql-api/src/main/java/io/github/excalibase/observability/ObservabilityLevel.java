package io.github.excalibase.observability;

import java.util.Locale;

/**
 * How much per-request monitoring the engine does ({@code excalibase.observability.level}).
 * {@link #FULL} keeps every metric and span; {@link #LITE} keeps the {@code http.server.requests} timer with the same
 * tags but makes no span per HTTP request, which costs far less CPU; {@link #OFF} drops both.
 */
public enum ObservabilityLevel {
    FULL, LITE, OFF;

    public static final String PROPERTY = "excalibase.observability.level";

    public boolean timesRequests() {
        return this != OFF;
    }

    public boolean tracesRequests() {
        return this == FULL;
    }

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
