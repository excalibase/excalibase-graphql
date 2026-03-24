package io.github.excalibase.extractor;

import io.github.excalibase.config.AppConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HeaderUserIdExtractorTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private AppConfig appConfig;

    @Mock
    private AppConfig.SecurityConfig securityConfig;

    private HeaderUserIdExtractor extractor;

    @BeforeEach
    void setUp() {
        lenient().when(appConfig.getSecurity()).thenReturn(securityConfig);
        lenient().when(securityConfig.getUserIdHeader()).thenReturn("X-User-Id");
        extractor = new HeaderUserIdExtractor(appConfig);
    }

    @Test
    void shouldExtractUserIdFromHeader() {
        when(request.getHeader("X-User-Id")).thenReturn("user-123");
        assertThat(extractor.extractUserId(request)).isEqualTo("user-123");
    }

    @Test
    void shouldTrimUserIdValue() {
        when(request.getHeader("X-User-Id")).thenReturn("  user-456  ");
        assertThat(extractor.extractUserId(request)).isEqualTo("user-456");
    }

    @Test
    void shouldReturnNullWhenHeaderMissing() {
        when(request.getHeader("X-User-Id")).thenReturn(null);
        assertThat(extractor.extractUserId(request)).isNull();
    }

    @Test
    void shouldReturnNullWhenHeaderEmpty() {
        when(request.getHeader("X-User-Id")).thenReturn("");
        assertThat(extractor.extractUserId(request)).isNull();
    }

    @Test
    void shouldReturnNullWhenHeaderOnlyWhitespace() {
        when(request.getHeader("X-User-Id")).thenReturn("   ");
        assertThat(extractor.extractUserId(request)).isNull();
    }

    @Test
    void shouldUseConfiguredUserIdHeader() {
        when(securityConfig.getUserIdHeader()).thenReturn("X-Custom-User");
        extractor = new HeaderUserIdExtractor(appConfig);
        when(request.getHeader("X-Custom-User")).thenReturn("custom-user-123");
        assertThat(extractor.extractUserId(request)).isEqualTo("custom-user-123");
    }

    @Test
    void shouldExtractAdditionalClaims() {
        Vector<String> headers = new Vector<>();
        headers.add("X-User-Id");
        headers.add("X-Claim-tenant_id");
        headers.add("X-Claim-department_id");
        headers.add("Content-Type");
        when(request.getHeaderNames()).thenReturn(headers.elements());
        when(request.getHeader("X-Claim-tenant_id")).thenReturn("acme-corp");
        when(request.getHeader("X-Claim-department_id")).thenReturn("engineering");

        Map<String, String> claims = extractor.extractAdditionalClaims(request);

        assertThat(claims).hasSize(2)
                .containsEntry("tenant_id", "acme-corp")
                .containsEntry("department_id", "engineering");
    }

    @Test
    void shouldHandleCaseInsensitiveClaimHeaders() {
        Vector<String> headers = new Vector<>();
        headers.add("X-CLAIM-ROLE");
        headers.add("x-claim-tenant_id");
        when(request.getHeaderNames()).thenReturn(headers.elements());
        when(request.getHeader("X-CLAIM-ROLE")).thenReturn("admin");
        when(request.getHeader("x-claim-tenant_id")).thenReturn("acme");

        Map<String, String> claims = extractor.extractAdditionalClaims(request);

        assertThat(claims).hasSize(2)
                .containsEntry("ROLE", "admin")
                .containsEntry("tenant_id", "acme");
    }

    @Test
    void shouldSkipNullAndEmptyClaimValues() {
        Vector<String> headers = new Vector<>();
        headers.add("X-Claim-valid");
        headers.add("X-Claim-nullval");
        headers.add("X-Claim-emptyval");
        when(request.getHeaderNames()).thenReturn(headers.elements());
        when(request.getHeader("X-Claim-valid")).thenReturn("ok");
        when(request.getHeader("X-Claim-nullval")).thenReturn(null);
        when(request.getHeader("X-Claim-emptyval")).thenReturn("  ");

        Map<String, String> claims = extractor.extractAdditionalClaims(request);

        assertThat(claims).hasSize(1).containsEntry("valid", "ok");
    }

    @Test
    void shouldIgnoreNonClaimHeaders() {
        Vector<String> headers = new Vector<>();
        headers.add("X-User-Id");
        headers.add("Content-Type");
        headers.add("Authorization");
        when(request.getHeaderNames()).thenReturn(headers.elements());

        Map<String, String> claims = extractor.extractAdditionalClaims(request);

        assertThat(claims).isEmpty();
    }

    @Test
    void shouldReturnEmptyMapWhenNoHeaderNames() {
        when(request.getHeaderNames()).thenReturn(null);
        assertThat(extractor.extractAdditionalClaims(request)).isEmpty();
    }
}
