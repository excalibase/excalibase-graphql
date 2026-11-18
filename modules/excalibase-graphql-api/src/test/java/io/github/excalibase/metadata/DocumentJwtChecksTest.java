package io.github.excalibase.metadata;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtVerificationException;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.TokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each check the verifier and the key loader make is pinned: take one away and one of these fails. */
class DocumentJwtChecksTest {

    private JwksStub idp;

    @BeforeEach
    void start() {
        idp = new JwksStub();
    }

    @AfterEach
    void stop() {
        idp.close();
    }

    private String settings() {
        return "issuer: \"" + idp.issuer() + "\"\naudience: " + JwksStub.AUDIENCE + "\njwksUrl: " + idp.jwksUrl() + "\n";
    }

    private static TokenVerifier verifier(String jwt) {
        return new MetadataDocumentParser(ReservedSchemas.fromConfig(""), "demo")
                .load("format: 1\nprojectId: demo\njwt:\n" + jwt.indent(2) + "tables: []\n", 1).verifier();
    }

    private void assertRefused(String token) {
        TokenVerifier verifier = verifier(settings());
        assertThatThrownBy(() -> verifier.verify(token, "demo")).isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void aTokenWithoutExp_isRefused() {
        assertRefused(idp.rs256(new JWTClaimsSet.Builder().subject("a").issuer(idp.issuer())
                .audience(JwksStub.AUDIENCE).claim("role", "user").build()));
    }

    @Test
    void aTokenNotYetValid_isRefused() {
        assertRefused(idp.rs256(idp.claims().claim("role", "user")
                .notBeforeTime(Date.from(Instant.now().plusSeconds(600))).build()));
    }

    @Test
    void aRefreshToken_isRefused() {
        assertRefused(idp.rs256(idp.claims().claim("role", "user").claim("token_use", "refresh").build()));
    }

    @Test
    void aTokenWithoutTheUserIdClaim_isRefused() {
        assertRefused(idp.rs256(new JWTClaimsSet.Builder().issuer(idp.issuer()).audience(JwksStub.AUDIENCE)
                .claim("role", "user").expirationTime(Date.from(Instant.now().plusSeconds(600))).build()));
    }

    @Test
    void anObjectValuedExtraClaim_isNotExposed() {
        JwtClaims claims = verifier(settings() + "claims:\n  extra:\n    org: org\n    tag: tag\n").verify(
                idp.rs256(idp.claims().claim("role", "user").claim("org", Map.of("id", 1)).claim("tag", "t").build()),
                "demo");

        assertThat(new Principal("user", false, claims, Map.of()).sessionVariables("demo"))
                .doesNotContainKey("x-excalibase-org").containsEntry("x-excalibase-tag", "t");
    }

    @Test
    void aKeyMarkedForEncryption_isNotATrustedSigningKey() throws Exception {
        RSAKey encryption = new RSAKeyGenerator(2048).keyUse(KeyUse.ENCRYPTION).keyID("enc-1").generate();
        idp.serve(idp.rsa.toPublicJWK(), encryption.toPublicJWK());
        String token = JwksStub.sign(new RSASSASigner(encryption), JWSAlgorithm.RS256, "enc-1",
                idp.claims().claim("role", "user").build());

        assertRefused(token);
    }

    @Test
    void anRsaKeyUnder2048Bits_isIgnored() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair pair = generator.generateKeyPair();
        RSAKey weak = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate())
                .keyID("weak-1").build();
        idp.serve(idp.rsa.toPublicJWK(), weak.toPublicJWK());
        String token = JwksStub.sign(new RSASSASigner(pair.getPrivate(), true), JWSAlgorithm.RS256, "weak-1",
                idp.claims().claim("role", "user").build());

        assertRefused(token);
    }

    @Test
    void aSecretUnder32Bytes_isRefused(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("secret"), "too-short");
        String jwt = "secret: file:" + file + "\naudience: a\n";

        assertThatThrownBy(() -> verifier(jwt)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void aDiscoveryDocumentNamingAnotherIssuer_isRefused() {
        idp.discoveryNames("https://someone-else/", idp.jwksUrl());
        String jwt = "issuer: \"" + idp.issuer() + "\"\naudience: a\n";

        assertThatThrownBy(() -> verifier(jwt)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRedirectFromTheKeySet_isNotFollowed() {
        try (JwksStub elsewhere = new JwksStub()) {
            idp.redirectTo(elsewhere.jwksUrl());
            String jwt = "issuer: \"" + idp.issuer() + "\"\naudience: a\njwksUrl: " + idp.movedUrl() + "\n";

            assertThatThrownBy(() -> verifier(jwt)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aRedirectFromTheDiscoveryDocument_isNotFollowed() {
        try (JwksStub elsewhere = new JwksStub()) {
            elsewhere.discoveryNames(idp.issuer(), elsewhere.jwksUrl());
            idp.redirectTo(elsewhere.issuer() + ".well-known/openid-configuration");
            String jwt = "issuer: \"" + idp.issuer() + "\"\naudience: a\n";

            assertThatThrownBy(() -> verifier(jwt)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aDiscoveredKeySetThatDowngradesFromAnHttpsIssuer_isRefused() {
        assertThatThrownBy(() -> JwtKeyLoader.checkedJwksUri("https://idp.auth.svc/", "http://127.0.0.1:9/jwks"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
        assertThat(JwtKeyLoader.checkedJwksUri("https://idp.auth.svc/", "https://idp.auth.svc/jwks"))
                .isEqualTo(URI.create("https://idp.auth.svc/jwks"));
    }
}
