package io.github.excalibase.metadata;

import java.util.List;
import java.util.Map;

/**
 * The optional {@code jwt:} section of the document. Phase 1 only reads and validates its shape so the
 * document format does not change when the engine starts applying it; nothing here reaches token
 * verification yet.
 *
 * @param sharedSecretFrom a reference ({@code env:NAME} or {@code file:/path}), never the secret itself
 */
public record JwtSection(String issuer, String audience, String jwksUrl, List<String> algorithms,
                         String sharedSecretFrom, Claims claims) {

    /** Where each value lives in the token; {@code extra} values become {@code X-Excalibase-<name>}. */
    public record Claims(String role, String defaultRole, String userId, Map<String, String> extra) {
    }
}
