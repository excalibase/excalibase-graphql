package io.github.excalibase.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for JwtUserIdExtractor.
 *
 * JWTs are constructed manually: base64url(header).base64url(payload).signature
 * Signature verification is skipped — the extractor only decodes the payload.
 */
@ExtendWith(MockitoExtension.class)
class JwtUserIdExtractorTest {

    @Mock
    private HttpServletRequest request;

    private JwtUserIdExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JwtUserIdExtractor(new ObjectMapper());
    }

    // --- helpers ---

    private String buildJwt(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""
                        {"alg":"HS256","typ":"JWT"}
                        """.strip().getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        return header + "." + payload + ".fakesignature";
    }

    private void mockBearer(String token) {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
    }

    // --- extractUserId ---

    @Test
    void shouldExtractSubAsUserId() {
        mockBearer(buildJwt("""
                {"sub":"user-123","role":"authenticated","email":"alice@example.com"}
                """.strip()));

        assertThat(extractor.extractUserId(request)).isEqualTo("user-123");
    }

    @Test
    void shouldFallbackToUserIdClaimWhenNoSub() {
        mockBearer(buildJwt("""
                {"user_id":"user-456","role":"authenticated"}
                """.strip()));

        assertThat(extractor.extractUserId(request)).isEqualTo("user-456");
    }

    @Test
    void shouldPreferSubOverUserId() {
        mockBearer(buildJwt("""
                {"sub":"from-sub","user_id":"from-user-id"}
                """.strip()));

        assertThat(extractor.extractUserId(request)).isEqualTo("from-sub");
    }

    @Test
    void shouldReturnNullWhenNoAuthHeader() {
        when(request.getHeader("Authorization")).thenReturn(null);
        assertThat(extractor.extractUserId(request)).isNull();
    }

    @Test
    void shouldReturnNullWhenNotBearerToken() {
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
        assertThat(extractor.extractUserId(request)).isNull();
    }

    @Test
    void shouldReturnNullWhenJwtMalformed() {
        when(request.getHeader("Authorization")).thenReturn("Bearer notajwt");
        assertThat(extractor.extractUserId(request)).isNull();
    }

    @Test
    void shouldReturnNullWhenPayloadNotJson() {
        String badJwt = "eyJhbGciOiJIUzI1NiJ9.bm90anNvbg.sig";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + badJwt);
        assertThat(extractor.extractUserId(request)).isNull();
    }

    @Test
    void shouldReturnNullWhenNoUserIdClaim() {
        mockBearer(buildJwt("""
                {"iss":"https://example.com","iat":1700000000,"exp":1700003600}
                """.strip()));

        assertThat(extractor.extractUserId(request)).isNull();
    }

    // --- extractAdditionalClaims ---

    @Test
    void shouldExtractAllNonReservedClaims() {
        mockBearer(buildJwt("""
                {"sub":"user-123","role":"authenticated","email":"alice@example.com",
                 "tenant_id":"acme","iss":"https://example.com","iat":1700000000,"exp":1700003600}
                """.strip().replaceAll("\\n\\s*", "")));

        Map<String, String> claims = extractor.extractAdditionalClaims(request);

        // role, email, tenant_id should be extracted
        assertThat(claims).containsEntry("role", "authenticated")
                .containsEntry("email", "alice@example.com")
                .containsEntry("tenant_id", "acme");

        // reserved claims should be skipped
        assertThat(claims).doesNotContainKeys("iss", "iat", "exp", "sub");
    }

    @Test
    void shouldSkipReservedJwtClaims() {
        mockBearer(buildJwt("""
                {"sub":"user","iss":"issuer","iat":1000,"exp":2000,"nbf":900,"jti":"abc","aud":"api"}
                """.strip()));

        Map<String, String> claims = extractor.extractAdditionalClaims(request);

        assertThat(claims).isEmpty();
    }

    @Test
    void shouldSkipSubAndUserIdFromAdditionalClaims() {
        mockBearer(buildJwt("""
                {"sub":"user-123","user_id":"user-123","role":"admin"}
                """.strip()));

        Map<String, String> claims = extractor.extractAdditionalClaims(request);

        // sub and user_id are used as userId, not as claims
        assertThat(claims).doesNotContainKeys("sub", "user_id");
        assertThat(claims).containsEntry("role", "admin");
    }

    @Test
    void shouldReturnEmptyClaimsWhenNoAuthHeader() {
        when(request.getHeader("Authorization")).thenReturn(null);
        assertThat(extractor.extractAdditionalClaims(request)).isEmpty();
    }

    @Test
    void shouldReturnEmptyClaimsWhenJwtMalformed() {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid");
        assertThat(extractor.extractAdditionalClaims(request)).isEmpty();
    }

    @Test
    void shouldHandleTypicalJwt() {
        // Typical JWT payload with common claims
        mockBearer(buildJwt("""
                {"iss":"https://auth.example.com/v1","sub":"abc-uuid-123",
                 "aud":"authenticated","role":"authenticated",
                 "email":"alice@example.com","app_metadata":{"provider":"email"},
                 "iat":1700000000,"exp":1700003600}
                """.strip().replaceAll("\\n\\s*", "")));

        assertThat(extractor.extractUserId(request)).isEqualTo("abc-uuid-123");

        Map<String, String> claims = extractor.extractAdditionalClaims(request);
        assertThat(claims).containsEntry("role", "authenticated")
                .containsEntry("email", "alice@example.com");
        assertThat(claims).doesNotContainKeys("iss", "sub", "aud", "iat", "exp");
    }
}
