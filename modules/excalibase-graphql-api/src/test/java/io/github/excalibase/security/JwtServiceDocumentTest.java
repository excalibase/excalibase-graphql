package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How the engine's one JwtService defers to the verifier a metadata document supplies, and only then. */
class JwtServiceDocumentTest {

    private static ECPrivateKey privateKey;
    private static ECPublicKey publicKey;
    private static final JwtClaims FROM_DOCUMENT = JwtClaims.of("doc-user", "demo", null, null, "user", null);

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        privateKey = (ECPrivateKey) pair.getPrivate();
        publicKey = (ECPublicKey) pair.getPublic();
    }

    private static String envToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("env-user").claim("role", "user")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    @Test
    void withoutADocumentVerifier_theServiceBehavesExactlyAsBefore() throws Exception {
        JwtService service = new JwtService(publicKey).requireAudience(false, null);

        assertThat(service.verify(envToken()).userId()).isEqualTo("env-user");
        assertThat(service.documentVerifier(() -> null).verify(envToken()).userId()).isEqualTo("env-user");
    }

    @Test
    void aDocumentVerifier_whenOneIsServed_decidesInsteadOfTheEnvKeys() throws Exception {
        String[] seen = new String[1];
        JwtService service = new JwtService(publicKey).requireAudience(false, null).documentVerifier(() -> (token, project) -> {
            seen[0] = project;
            return FROM_DOCUMENT;
        });

        assertThat(service.verify("whatever", "demo")).isSameAs(FROM_DOCUMENT);
        assertThat(seen[0]).isEqualTo("demo");
    }

    @Test
    void aDocumentOnlyService_refusesEverything_untilTheDocumentSuppliesAVerifier() throws Exception {
        JwtService nothingYet = JwtService.documentOnly().documentVerifier(() -> null);

        assertThatThrownBy(() -> nothingYet.verify(envToken(), "demo")).isInstanceOf(JwtVerificationException.class);
        assertThat(JwtService.documentOnly().documentVerifier(() -> (token, project) -> FROM_DOCUMENT)
                .verify("x", "demo")).isSameAs(FROM_DOCUMENT);
    }
}
