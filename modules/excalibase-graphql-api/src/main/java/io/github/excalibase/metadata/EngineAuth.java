package io.github.excalibase.metadata;

import io.github.excalibase.security.TokenVerifier;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The token settings the engine itself holds, which a document {@code jwt:} section may not add to: none, the
 * legacy {@code app.security.auth.*} ones (always refused beside a section), or {@code app.auth.*} (refused too,
 * unless the section says exactly the same, so a deployment can move it out of the document without a gap).
 */
public final class EngineAuth {

    public static final EngineAuth NONE = new EngineAuth(false, null, null);
    private static final EngineAuth LEGACY = new EngineAuth(true, null, null);

    private final boolean legacy;
    private final JwtSettings settings;
    private final byte[] secret;

    private EngineAuth(boolean legacy, JwtSettings settings, byte[] secret) {
        this.legacy = legacy;
        this.settings = settings;
        this.secret = secret;
    }

    public static EngineAuth of(boolean legacy) {
        return legacy ? LEGACY : NONE;
    }

    /** @param secret the resolved shared secret; null for a key set */
    static EngineAuth engine(JwtSettings settings, byte[] secret) {
        return new EngineAuth(false, settings, secret);
    }

    public boolean legacy() {
        return legacy;
    }

    public boolean engine() {
        return settings != null;
    }

    /** Some engine-level token source exists, so tokens are checked whatever the document says. */
    public boolean configured() {
        return legacy || settings != null;
    }

    public JwtSettings settings() {
        return settings;
    }

    /** Fetches the keys (or uses the secret) now, so settings that cannot work refuse the start. */
    public TokenVerifier verifier() {
        return DocumentJwtVerifier.create(settings, secret);
    }

    /**
     * What differs between these settings and a document section, by name, never by value. Empty means identical:
     * every non-secret field is equal and the key material is the same bytes.
     *
     * @param documentSecret the document's resolved secret, null when it names a key set
     */
    String differences(JwtSettings document, byte[] documentSecret) {
        var differing = new ArrayList<String>();
        addIf(differing, "issuer", Objects.equals(settings.issuer(), document.issuer()));
        addIf(differing, "jwksUrl", Objects.equals(settings.jwksUrl(), document.jwksUrl()));
        addIf(differing, "algorithms", new HashSet<>(settings.algorithms()).equals(new HashSet<>(document.algorithms())));
        addIf(differing, "audience", new HashSet<>(settings.audience()).equals(new HashSet<>(document.audience())));
        addIf(differing, "clockSkew", settings.clockSkew().equals(document.clockSkew()));
        addClaims(differing, document.claims());
        boolean sameKind = (secret == null) == (documentSecret == null);
        boolean sameKey = sameKind && (secret == null || MessageDigest.isEqual(secret, documentSecret));
        addIf(differing, "key", sameKey);
        return String.join(", ", differing);
    }

    private void addClaims(List<String> differing, JwtSettings.Claims other) {
        JwtSettings.Claims mine = settings.claims();
        addIf(differing, "claims.role", mine.role().equals(other.role()));
        addIf(differing, "claims.defaultRole", Objects.equals(mine.defaultRole(), other.defaultRole()));
        addIf(differing, "claims.userId", mine.userId().equals(other.userId()));
        addIf(differing, "claims.extra", Map.copyOf(mine.extra()).equals(Map.copyOf(other.extra())));
    }

    private static void addIf(List<String> differing, String field, boolean same) {
        if (!same) {
            differing.add(field);
        }
    }
}
