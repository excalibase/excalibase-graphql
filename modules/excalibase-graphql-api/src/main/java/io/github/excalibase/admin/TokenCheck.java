package io.github.excalibase.admin;

import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.Header;
import com.nimbusds.jose.util.Base64URL;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtVerificationException;
import io.github.excalibase.security.Principal;
import io.github.excalibase.security.RoleNotAllowedException;
import io.github.excalibase.security.RoleResolver;
import io.github.excalibase.security.TokenVerifier;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs a pasted token through the verifier the engine serves with and says why it fails, as one name from
 * {@link Reason} and nothing else. The verifier's own text (which can hold a claim) is mapped to the name and dropped;
 * the token is never kept, logged or put in an answer.
 */
public final class TokenCheck {

    /** The largest token this accepts; a real one is well under 4 KB. */
    public static final int MAX_BYTES = 8 * 1024;

    public enum Reason {
        EXPIRED, NOT_YET_VALID, BAD_SIGNATURE, ALGORITHM, ISSUER, AUDIENCE, ROLE_CLAIM, MISSING_USER_ID, MALFORMED,
        UNSUPPORTED_TOKEN_TYPE, NO_VERIFIER;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the verifiers say when they refuse without a code of their own, by the start of their text. */
    private static final List<Map.Entry<String, Reason>> BY_MESSAGE = List.of(
            Map.entry("JWT token expired", Reason.EXPIRED),
            Map.entry("JWT token not yet valid", Reason.NOT_YET_VALID),
            Map.entry("JWT signature verification failed", Reason.BAD_SIGNATURE),
            Map.entry("JWT algorithm is not accepted", Reason.ALGORITHM),
            Map.entry("JWT issuer not accepted", Reason.ISSUER),
            Map.entry("Missing required claim", Reason.MISSING_USER_ID),
            Map.entry("No JWT verifier is configured", Reason.NO_VERIFIER));

    private TokenCheck() {
    }

    /**
     * @param verifier  what the engine serves tokens with; null when it has none
     * @param projectId the project a token is bound to, or null
     */
    public static Map<String, Object> run(TokenVerifier verifier, byte[] body, String projectId) {
        if (verifier == null) {
            return refused(Reason.NO_VERIFIER);
        }
        String token = text(body);
        if (token == null || token.isEmpty()) {
            return refused(Reason.MALFORMED);
        }
        if (unsigned(token)) {
            return refused(Reason.ALGORITHM);
        }
        try {
            return accepted(verifier.verify(token, projectId), projectId);
        } catch (JwtVerificationException e) {
            return refused(reasonOf(e));
        } catch (RoleNotAllowedException _) {
            return refused(Reason.ROLE_CLAIM);
        } catch (RuntimeException _) {
            return refused(Reason.MALFORMED);
        }
    }

    private static String text(byte[] body) {
        if (body == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString().strip();
        } catch (CharacterCodingException _) {
            return null;
        }
    }

    /** A header that says {@code alg: none} is not a signed token at all; the verifier would only call it malformed. */
    private static boolean unsigned(String token) {
        int dot = token.indexOf('.');
        if (dot < 1) {
            return false;
        }
        try {
            return Algorithm.NONE.equals(Header.parse(Base64URL.from(token.substring(0, dot))).getAlgorithm());
        } catch (ParseException | RuntimeException _) {
            return false;
        }
    }

    static Reason reasonOf(JwtVerificationException refusal) {
        return switch (String.valueOf(refusal.code())) {
            case JwtVerificationException.AUD_MISMATCH -> Reason.AUDIENCE;
            case JwtVerificationException.REFRESH_TOKEN_NOT_ACCEPTED, JwtVerificationException.NOT_AN_ACCESS_TOKEN ->
                    Reason.UNSUPPORTED_TOKEN_TYPE;
            case JwtVerificationException.INVALID_ROLE_CLAIM -> Reason.ROLE_CLAIM;
            default -> byMessage(String.valueOf(refusal.getMessage()));
        };
    }

    private static Reason byMessage(String message) {
        return BY_MESSAGE.stream().filter(entry -> message.startsWith(entry.getKey())).map(Map.Entry::getValue)
                .findFirst().orElse(Reason.MALFORMED);
    }

    private static Map<String, Object> refused(Reason reason) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("valid", false);
        answer.put("reason", reason.id());
        answer.put("role", null);
        answer.put("allowedRoles", List.of());
        answer.put("userId", null);
        answer.put("sessionVariables", Map.of());
        return answer;
    }

    private static Map<String, Object> accepted(JwtClaims claims, String projectId) {
        Principal principal = RoleResolver.resolve(claims, null, Map.of());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("valid", true);
        answer.put("reason", null);
        answer.put("role", principal.role());
        answer.put("allowedRoles", claims.allowedRoles().stream().sorted().toList());
        answer.put("userId", claims.userId());
        answer.put("sessionVariables", new TreeMap<>(principal.sessionVariables(projectId)));
        return answer;
    }
}
