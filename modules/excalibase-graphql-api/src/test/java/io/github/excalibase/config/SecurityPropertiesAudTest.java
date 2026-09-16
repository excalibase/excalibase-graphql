package io.github.excalibase.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXC-11: the audience requirement must be on unless an operator deliberately
 * turns it off, so an unset property can never silently weaken verification.
 */
class SecurityPropertiesAudTest {

    private SecurityProperties.Auth auth(Boolean requireAud) {
        return new SecurityProperties.Auth("https://jwks.test", null, null, null, requireAud, null);
    }

    @Test
    @DisplayName("an absent require-aud property defaults to enforcing the audience")
    void absentProperty_defaultsToRequired() {
        assertTrue(auth(null).requireAudOrDefault());
    }

    @Test
    @DisplayName("require-aud=true enforces the audience")
    void explicitTrue_required() {
        assertTrue(auth(true).requireAudOrDefault());
    }

    @Test
    @DisplayName("require-aud=false disables the audience check for a phased rollout")
    void explicitFalse_notRequired() {
        assertFalse(auth(false).requireAudOrDefault());
    }
}
