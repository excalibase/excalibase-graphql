package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code role} and {@code allowed_roles} claims, per docs/features/permissions.md §1.1. */
class JwtServiceRoleClaimTest {

    private static ECPrivateKey privateKey;
    private static JwtService jwtService;

    @BeforeAll
    static void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (ECPrivateKey) keyPair.getPrivate();
        jwtService = new JwtService((ECPublicKey) keyPair.getPublic()).requireAudience(false, null);
    }

    private static JWTClaimsSet.Builder baseClaims() {
        return new JWTClaimsSet.Builder()
                .subject("alice@test.com")
                .claim("userId", "u-1")
                .claim("projectId", "proj-a")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)));
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private static void assertInvalidRoleClaim(JWTClaimsSet claims) throws Exception {
        String token = sign(claims);
        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(JwtVerificationException.class)
                .extracting(thrown -> ((JwtVerificationException) thrown).code())
                .isEqualTo(JwtVerificationException.INVALID_ROLE_CLAIM);
    }

    @Test
    void roleClaim_whenAbsent_isRefusedRatherThanDefaulted() throws Exception {
        assertInvalidRoleClaim(baseClaims().build());
    }

    @Test
    void roleClaim_whenNotAString_isRefused() throws Exception {
        assertInvalidRoleClaim(baseClaims().claim("role", 7).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "User", "1user", "user-x", "user x", "_user",
            "a234567890123456789012345678901234567890123456789012345678901234"})
    void roleClaim_whenNotALowercaseIdentifier_isRefused(String role) throws Exception {
        assertInvalidRoleClaim(baseClaims().claim("role", role).build());
    }

    @Test
    void roleClaim_whenSixtyThreeCharacters_isAccepted() throws Exception {
        String role = "a" + "b".repeat(62);
        JwtClaims claims = jwtService.verify(sign(baseClaims().claim("role", role).build()));
        assertThat(claims.role()).isEqualTo(role);
    }

    @Test
    void allowedRoles_whenAbsent_areOnlyTheDefaultRole() throws Exception {
        JwtClaims claims = jwtService.verify(sign(baseClaims().claim("role", "user").build()));

        assertThat(claims.role()).isEqualTo("user");
        assertThat(claims.allowedRoles()).containsExactly("user");
    }

    @Test
    void allowedRoles_whenPresentAndContainingTheRole_areCarried() throws Exception {
        JwtClaims claims = jwtService.verify(sign(baseClaims()
                .claim("role", "user").claim("allowed_roles", List.of("user", "editor")).build()));

        assertThat(claims.allowedRoles()).isEqualTo(Set.of("user", "editor"));
    }

    @Test
    void allowedRoles_whenMissingTheDefaultRole_isRefused() throws Exception {
        assertInvalidRoleClaim(baseClaims()
                .claim("role", "user").claim("allowed_roles", List.of("editor")).build());
    }

    @Test
    void allowedRoles_whenNotAnArray_isRefused() throws Exception {
        assertInvalidRoleClaim(baseClaims().claim("role", "user").claim("allowed_roles", "user").build());
    }

    @Test
    void allowedRoles_whenAnEntryIsNotAValidRole_isRefused() throws Exception {
        assertInvalidRoleClaim(baseClaims()
                .claim("role", "user").claim("allowed_roles", List.of("user", "Admin")).build());
    }

    @Test
    void allowedRoles_whenAnEntryIsNotAString_isRefused() throws Exception {
        assertInvalidRoleClaim(baseClaims()
                .claim("role", "user").claim("allowed_roles", List.of("user", 3)).build());
    }

    @Test
    void serviceRole_withServiceScope_isAccepted() throws Exception {
        JwtClaims claims = jwtService.verify(sign(baseClaims()
                .claim("role", "service").claim("scope", "service").build()));

        assertThat(claims.role()).isEqualTo("service");
        assertThat(claims.scope()).isEqualTo("service");
    }

    @Test
    void serviceRole_withoutServiceScope_isRefused() throws Exception {
        assertInvalidRoleClaim(baseClaims().claim("role", "service").build());
        assertInvalidRoleClaim(baseClaims().claim("role", "service").claim("scope", "public").build());
    }

    @Test
    void serviceInAllowedRoles_withoutServiceScope_isRefused() throws Exception {
        assertInvalidRoleClaim(baseClaims()
                .claim("role", "user").claim("allowed_roles", List.of("user", "service")).build());
    }
}
