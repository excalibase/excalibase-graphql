package io.github.excalibase.metadata;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.security.JwksKeys;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtVerificationException;
import io.github.excalibase.security.TokenVerifier;

import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Verifies tokens against the document's JWT settings. The algorithm comes from the settings, never from the
 * token: a header naming another algorithm, {@code none} or HMAC against a key set is refused before any key
 * is tried, which is what closes algorithm confusion. A key is chosen by the token's {@code kid}; a token naming
 * a key the provider does not publish is refused, not tried against the others.
 */
public final class DocumentJwtVerifier implements TokenVerifier {

    private final JwtSettings settings;
    private final Set<JWSAlgorithm> algorithms;
    private final JwksKeys keys;
    private final byte[] secret;
    private final DocumentClaims claims;

    private DocumentJwtVerifier(JwtSettings settings, JwksKeys keys, byte[] secret) {
        this.settings = settings;
        this.algorithms = settings.algorithms().stream().map(JWSAlgorithm::parse).collect(Collectors.toSet());
        this.keys = keys;
        this.secret = secret;
        this.claims = new DocumentClaims(settings.claims());
    }

    /**
     * Fetches the keys (or reads the secret) now, so a document whose provider cannot be reached is not served.
     *
     * @throws IllegalStateException the keys or the secret cannot be had right now
     */
    public static DocumentJwtVerifier create(JwtSettings settings) {
        return create(settings, System::currentTimeMillis);
    }

    /** The clock paces key refreshes; a test moves it instead of waiting. */
    static DocumentJwtVerifier create(JwtSettings settings, LongSupplier clock) {
        if (settings.secretRef() != null) {
            return new DocumentJwtVerifier(settings, null, JwtKeyLoader.secret(settings.secretRef(), System::getenv));
        }
        return new DocumentJwtVerifier(settings, JwtKeyLoader.keys(settings, clock), null);
    }

    @Override
    public JwtClaims verify(String token, String expectedProjectId) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!algorithms.contains(jwt.getHeader().getAlgorithm())) {
                throw new JwtVerificationException("JWT algorithm is not accepted");
            }
            if (!signatureHolds(jwt)) {
                throw new JwtVerificationException("JWT signature verification failed");
            }
            JWTClaimsSet verified = jwt.getJWTClaimsSet();
            checkTimeWindow(verified);
            checkIssuerAndAudience(verified);
            return claims.map(verified, expectedProjectId);
        } catch (JwtVerificationException e) {
            throw e;
        } catch (ParseException | JOSEException e) {
            throw new JwtVerificationException("Invalid JWT: " + e.getMessage(), e);
        }
    }

    private boolean signatureHolds(SignedJWT jwt) throws JOSEException {
        if (secret != null) {
            return jwt.verify(new MACVerifier(secret));
        }
        String keyId = jwt.getHeader().getKeyID();
        for (JwksKeys.Key candidate : keys.keysFor(keyId)) {
            if ((keyId == null || keyId.equals(candidate.keyId())) && verifies(jwt, candidate.publicKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean verifies(SignedJWT jwt, PublicKey key) {
        try {
            JWSVerifier verifier = switch (key) {
                case RSAPublicKey rsa -> new RSASSAVerifier(rsa);
                case ECPublicKey ec -> new ECDSAVerifier(ec);
                default -> null;
            };
            return verifier != null && jwt.verify(verifier);
        } catch (JOSEException _) {
            // a key that cannot verify this algorithm (an EC key for RS256, a curve for another ES) just does not match
            return false;
        }
    }

    private void checkTimeWindow(JWTClaimsSet verified) {
        Date expires = verified.getExpirationTime();
        if (expires == null) {
            throw new JwtVerificationException("JWT missing required 'exp' claim");
        }
        long now = System.currentTimeMillis();
        long skew = settings.clockSkew().toMillis();
        if (now > expires.getTime() + skew) {
            throw new JwtVerificationException("JWT token expired");
        }
        Date notBefore = verified.getNotBeforeTime();
        if (notBefore != null && now + skew < notBefore.getTime()) {
            throw new JwtVerificationException("JWT token not yet valid");
        }
    }

    private void checkIssuerAndAudience(JWTClaimsSet verified) {
        if (settings.issuer() != null && !settings.issuer().equals(verified.getIssuer())) {
            throw new JwtVerificationException("JWT issuer not accepted");
        }
        List<String> audience = verified.getAudience();
        if (audience == null || audience.stream().noneMatch(settings.audience()::contains)) {
            throw new JwtVerificationException(JwtVerificationException.AUD_MISMATCH,
                    "JWT audience does not cover this project");
        }
        if ("refresh".equals(verified.getClaim("token_use"))) {
            throw new JwtVerificationException(JwtVerificationException.REFRESH_TOKEN_NOT_ACCEPTED,
                    "Refresh token is not accepted on API calls");
        }
    }
}
