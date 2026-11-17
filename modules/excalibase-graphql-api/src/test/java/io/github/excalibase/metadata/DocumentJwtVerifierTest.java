package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtVerificationException;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.RoleNotAllowedException;
import io.github.excalibase.security.RoleResolver;
import io.github.excalibase.security.TokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the engine accepts once the document carries JWT settings: keys, algorithms, audience, claim paths. */
class DocumentJwtVerifierTest {

    private JwksStub idp;

    @BeforeEach
    void startIdp() {
        idp = new JwksStub();
    }

    @AfterEach
    void stopIdp() {
        idp.close();
    }

    private String base() {
        return "issuer: \"" + idp.issuer() + "\"\naudience: " + JwksStub.AUDIENCE + "\njwksUrl: " + idp.jwksUrl() + "\n";
    }

    private static String document(String jwt) {
        return "format: 1\nprojectId: demo\njwt:\n" + jwt.indent(2) + "tables: []\n";
    }

    private static TokenVerifier verifier(String jwt) {
        return new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo").load(document(jwt), 1).verifier();
    }

    private JwtClaims verify(String extraSettings, String token) {
        return verifier(base() + extraSettings).verify(token, "demo");
    }

    private void assertRefused(String extraSettings, String token) {
        assertThatThrownBy(() -> verify(extraSettings, token)).isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void rs256_isAcceptedFromTheJwks() {
        assertThat(verify("", idp.rs256(idp.claims().claim("role", "user").build())).role()).isEqualTo("user");
    }

    @Test
    void es256_isAcceptedFromTheJwks() {
        assertThat(verify("", idp.es256(idp.claims().claim("role", "user").build())).role()).isEqualTo("user");
    }

    @Test
    void anUnsignedToken_isRefused() {
        assertRefused("algorithms: [RS256, ES256]\n", JwksStub.unsigned(idp.claims().claim("role", "user").build()));
    }

    @Test
    void hs256SignedWithThePublicKey_isRefused() {
        byte[] publicKeyBytes = idp.rsa.toPublicJWK().toString().getBytes();
        String forged = JwksStub.hs256(Arrays.copyOf(publicKeyBytes, 64),
                idp.claims().claim("role", "admin").build());

        assertRefused("", forged);
    }

    @Test
    void aTokenSignedByAnotherKey_isRefused_whateverKidItNames() throws Exception {
        RSAKey stranger = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
        String forged = JwksStub.sign(new RSASSASigner(stranger),
                JWSAlgorithm.RS256, "rsa-1", idp.claims().claim("role", "user").build());
        String unknownKid = JwksStub.sign(new RSASSASigner(idp.rsa),
                JWSAlgorithm.RS256, "no-such-key", idp.claims().claim("role", "user").build());

        assertRefused("", forged);
        assertRefused("", unknownKid);
    }

    @Test
    void anAlgorithmOutsideTheAllowList_isRefused() {
        assertRefused("algorithms: [RS256]\n", idp.es256(idp.claims().claim("role", "user").build()));
    }

    @Test
    void anExpiredToken_isRefused() {
        JWTClaimsSet expired = idp.claims().claim("role", "user")
                .expirationTime(Date.from(Instant.now().minusSeconds(600))).build();

        assertRefused("", idp.rs256(expired));
    }

    @Test
    void aSmallClockSkew_isTolerated_aLargeOneIsNot() {
        JWTClaimsSet barelyExpired = idp.claims().claim("role", "user")
                .expirationTime(Date.from(Instant.now().minusSeconds(10))).build();

        assertThat(verify("", idp.rs256(barelyExpired)).role()).isEqualTo("user");
        assertRefused("clockSkew: 0\n", idp.rs256(barelyExpired));
    }

    @Test
    void aWrongAudience_orAWrongIssuer_isRefused() {
        assertRefused("", idp.rs256(idp.claims().claim("role", "user").audience("https://other").build()));
        assertRefused("", idp.rs256(idp.claims().claim("role", "user").issuer("https://elsewhere/").build()));
    }

    @Test
    void anAudienceList_acceptsAnyOfItsEntries() {
        String token = idp.rs256(idp.claims().claim("role", "user").audience(List.of("x", JwksStub.AUDIENCE)).build());

        assertThat(verify("", token).role()).isEqualTo("user");
        TokenVerifier listed = verifier("issuer: \"" + idp.issuer() + "\"\njwksUrl: " + idp.jwksUrl()
                + "\naudience: [a, b]\n");
        String otherAudience = idp.rs256(idp.claims().claim("role", "user").audience("c").build());
        assertThatThrownBy(() -> listed.verify(otherAudience, "demo")).isInstanceOf(JwtVerificationException.class);
        assertThat(listed.verify(idp.rs256(idp.claims().claim("role", "user").audience("b").build()), "demo").role())
                .isEqualTo("user");
    }

    @Test
    void theIssuerAloneIsEnoughToFindTheKeys_throughDiscovery() {
        TokenVerifier discovered = verifier("issuer: \"" + idp.issuer() + "\"\naudience: " + JwksStub.AUDIENCE + "\n");

        assertThat(discovered.verify(idp.rs256(idp.claims().claim("role", "user").build()), "demo").role())
                .isEqualTo("user");
    }

    @Test
    void roleAndUserIdPaths_followNestedAndNamespacedClaims() {
        String token = idp.rs256(idp.claims().claim("role", "ignored")
                .claim("https://hasura.io/jwt/claims", Map.of("x-hasura-default-role", "editor"))
                .claim("profile", Map.of("id", "p-9")).build());

        JwtClaims claims = verify("""
                claims:
                  role: https://hasura.io/jwt/claims.x-hasura-default-role
                  userId: profile.id
                """, token);

        assertThat(claims.role()).isEqualTo("editor");
        assertThat(claims.userId()).isEqualTo("p-9");
    }

    @Test
    void theUserId_isTheSubjectByDefault() {
        assertThat(verify("", idp.rs256(idp.claims().claim("role", "user").build())).userId())
                .isEqualTo("auth0|alice");
    }

    @Test
    void aRoleList_isTheAllowedRoles_andTheDocumentsDefaultRoleIsActive() {
        String token = idp.rs256(idp.claims().claim("realm_access", Map.of("roles",
                List.of("Offline Access", "editor", "viewer", "default-roles-x"))).build());

        JwtClaims claims = verify("claims:\n  role: realm_access.roles\n  defaultRole: viewer\n", token);

        assertThat(claims.role()).isEqualTo("viewer");
        assertThat(claims.allowedRoles()).containsExactlyInAnyOrder("editor", "viewer");
    }

    @Test
    void theRoleHeader_picksARoleInTheList_notOneOutsideIt() {
        String token = idp.rs256(idp.claims().claim("roles", List.of("editor", "viewer")).build());
        JwtClaims claims = verify("claims:\n  role: roles\n  defaultRole: viewer\n", token);

        assertThat(RoleResolver.resolve(claims, "editor", Map.of()).role()).isEqualTo("editor");
        assertThat(RoleResolver.resolve(claims, null, Map.of()).role()).isEqualTo("viewer");
        assertThatThrownBy(() -> RoleResolver.resolve(claims, "admin", Map.of()))
                .isInstanceOf(RoleNotAllowedException.class);
    }

    @Test
    void aRoleList_withoutTheDefaultRoleInIt_isRefused() {
        String token = idp.rs256(idp.claims().claim("roles", List.of("editor")).build());

        assertRefused("claims:\n  role: roles\n  defaultRole: viewer\n", token);
        assertRefused("claims:\n  role: roles\n", token);
    }

    @Test
    void theServiceRole_isNeverGrantedByAnOutsideToken() {
        assertRefused("", idp.rs256(idp.claims().claim("role", "service").build()));

        JwtClaims listed = verify("claims:\n  role: roles\n  defaultRole: editor\n",
                idp.rs256(idp.claims().claim("roles", List.of("editor", "service")).build()));

        assertThat(listed.allowedRoles()).containsExactly("editor");
        String serviceDefault = base() + "claims:\n  role: roles\n  defaultRole: service\n";
        assertThatThrownBy(() -> verifier(serviceDefault)).isInstanceOf(MetadataDocumentException.class);
    }

    @Test
    void anExtraClaim_becomesASessionVariableARuleCanUse() {
        String token = idp.rs256(idp.claims().claim("role", "user").claim("https://demo/org_id", "org-7")
                .claim("groups", List.of("a", "b")).build());

        JwtClaims claims = verify("claims:\n  extra:\n    org-id: https://demo/org_id\n    groups: groups\n", token);
        Map<String, String> variables = new Principal("user", false, claims, Map.of()).sessionVariables("demo");

        assertThat(variables).containsEntry("x-excalibase-org-id", "org-7")
                .containsEntry("x-excalibase-groups", "{\"a\",\"b\"}")
                .containsEntry("x-excalibase-user-id", "auth0|alice");
    }

    @Test
    void aTokenClaimCannotPoseAsASessionVariableTheDocumentDidNotMap() {
        String token = idp.rs256(idp.claims().claim("role", "user").claim("org-id", "stolen")
                .claim("userId", "someone-else").build());

        Map<String, String> variables = new Principal("user", false, verify("", token), Map.of())
                .sessionVariables("demo");

        assertThat(variables).doesNotContainKey("x-excalibase-org-id")
                .containsEntry("x-excalibase-user-id", "auth0|alice");
    }

    /** A verifier whose clock the test moves, so the key refresh pace can be crossed without waiting. */
    private TokenVerifier verifierWithClock(AtomicLong clock) throws Exception {
        JsonNode settings = new ObjectMapper().readTree("{\"issuer\":\"" + idp.issuer() + "\",\"jwksUrl\":\""
                + idp.jwksUrl() + "\",\"audience\":\"" + JwksStub.AUDIENCE + "\"}");
        return DocumentJwtVerifier.create(JwtSettingsParser.parse(settings), clock::get);
    }

    @Test
    void aRotatedKey_isPickedUpOnTheFirstTokenNamingIt_oncePastTheRefreshPace() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        TokenVerifier verifier = verifierWithClock(clock);
        ECKey rotated = new ECKeyGenerator(Curve.P_256).keyID("ec-2").generate();
        idp.serve(idp.rsa.toPublicJWK(), rotated.toPublicJWK());
        String token = JwksStub.sign(new ECDSASigner(rotated), JWSAlgorithm.ES256, "ec-2",
                idp.claims().claim("role", "user").build());

        assertThatThrownBy(() -> verifier.verify(token, "demo")).as("inside the refresh pace")
                .isInstanceOf(JwtVerificationException.class);
        clock.addAndGet(11_000);

        assertThat(verifier.verify(token, "demo").role()).isEqualTo("user");
    }

    @Test
    void whenTheJwksCannotBeFetchedAgain_theLastGoodKeysKeepVerifying() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        TokenVerifier verifier = verifierWithClock(clock);
        idp.failFromNowOn();
        clock.addAndGet(11_000);
        String unknownKid = JwksStub.sign(new RSASSASigner(idp.rsa), JWSAlgorithm.RS256, "rotated-but-unreachable",
                idp.claims().claim("role", "user").build());
        int hitsBefore = idp.jwksHits.get();

        assertThatThrownBy(() -> verifier.verify(unknownKid, "demo")).isInstanceOf(JwtVerificationException.class);
        assertThat(idp.jwksHits.get()).as("the unknown key id did make it try again").isGreaterThan(hitsBefore);
        assertThat(verifier.verify(idp.rs256(idp.claims().claim("role", "user").build()), "demo").role())
                .isEqualTo("user");
    }

    @Test
    void aSharedSecret_fromAFile_verifiesHs256_andNothingElse() throws Exception {
        Path file = Files.createTempFile("jwt-secret", ".txt");
        byte[] secret = "0123456789abcdef0123456789abcdef-secret".getBytes();
        Files.write(file, secret);
        String settings = "secret: file:" + file + "\naudience: " + JwksStub.AUDIENCE + "\n";

        TokenVerifier verifier = verifier(settings);

        assertThat(verifier.verify(JwksStub.hs256(secret, idp.claims().claim("role", "user").build()), "demo").role())
                .isEqualTo("user");
        String signedWithAKeySet = idp.rs256(idp.claims().claim("role", "user").build());
        assertThatThrownBy(() -> verifier.verify(signedWithAKeySet, "demo")).isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void claimPath_prefersTheWholeKey_thenSplitsOnDots() {
        Map<String, Object> claims = Map.of("a.b", "whole", "a", Map.of("b", "nested", "list", List.of("x")),
                "https://x.io/c", Map.of("d", Set.of()));

        assertThat(ClaimPath.resolve(claims, "a.b")).isEqualTo("whole");
        assertThat(ClaimPath.resolve(claims, "a.list")).isEqualTo(List.of("x"));
        assertThat(ClaimPath.resolve(claims, "https://x.io/c.d")).isEqualTo(Set.of());
        assertThat(ClaimPath.resolve(claims, "a.missing")).isNull();
        assertThat(ClaimPath.resolve(claims, "nothing")).isNull();
    }
}
