package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A name that is not in the allow-list cannot silently do nothing: start (and validate) refuse it. */
class AuthEnvAllowListTest {

    @Test
    void anUnknownEnvironmentName_refusesStart() {
        for (String name : List.of("APP_AUTH_CLAIM_ROLE", "APP_AUTH_AUDIENCES", "APP_AUTH_", "APP_AUTH_HMAC_SECRET")) {
            assertThatThrownBy(() -> AuthSettingsReader.fromEnv(Map.of(name, "x")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(name)
                    .hasMessageContaining("APP_AUTH_CLAIMS_ROLE");
        }
    }

    @Test
    void aLegacyOrOtherName_isNotTouched() {
        assertThatCode(() -> AuthSettingsReader.fromEnv(Map.of("APP_SECURITY_AUTH_JWKS_URL", "x", "APP_AUTHOR", "y",
                "AUTH_AUD_PREFIX", "p"))).doesNotThrowAnyException();
    }

    @Test
    void anUnknownPropertyKey_refusesStart_eachSpellingOfAKnownOneIsFine() {
        assertThatThrownBy(() -> AuthSettingsReader.refuseUnknown(Set.of(), Set.of("claim.role")))
                .hasMessageContaining("app.auth.claim.role");
        assertThatCode(() -> AuthSettingsReader.refuseUnknown(Set.of(),
                Set.of("claims.user-id", "claims.user.id", "claims-user-id", "jwks-url", "jwksUrl", "issuer",
                        "secret-file", "clock-skew", "claims.default-role", "claims.extra", "claims.role", "audience",
                        "algorithms", "secret"))).doesNotThrowAnyException();
    }

    @Test
    void aMutationThatDropsTheCheck_wouldBeSeen() {
        // the allow-list is the only thing between a typo and a silently missing audience
        assertThatThrownBy(() -> AuthSettingsReader.fromEnv(Map.of("APP_AUTH_SECRET", AuthSettingsReaderTest.SECRET,
                "APP_AUTH_AUDIENCE", "api", "APP_AUTH_ALGORITHM", "HS512"))).hasMessageContaining("APP_AUTH_ALGORITHM");
        assertThat(AuthSettingsReader.ENV_NAMES).doesNotContain("APP_AUTH_ALGORITHM");
    }
}
