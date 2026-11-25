package io.github.excalibase.metadata;

import java.time.Duration;

/**
 * What the {@code url} source is configured with.
 *
 * @param token     a literal bearer token (env {@code METADATA_URL_TOKEN}); blank when none
 * @param tokenFile a file holding the token, re-read so a rotation needs no restart; blank when none
 */
public record UrlSettings(String location, String token, String tokenFile, boolean allowInsecure,
                          Duration pollInterval, Duration connectTimeout, Duration readTimeout) {

    @Override
    public String toString() {
        return "UrlSettings[token and location withheld]";
    }
}
