package io.github.excalibase.filter;

import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.DatabaseType;
import io.github.excalibase.extractor.IUserIdExtractor;
import io.github.excalibase.service.IUserContextService;
import io.github.excalibase.service.ServiceLookup;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserContextFilterTest {

    @Mock private ServiceLookup serviceLookup;
    @Mock private AppConfig appConfig;
    @Mock private AppConfig.SecurityConfig securityConfig;
    @Mock private IUserIdExtractor userIdExtractor;
    @Mock private IUserContextService userContextService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private UserContextFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        when(appConfig.getSecurity()).thenReturn(securityConfig);
        when(securityConfig.isUserContextEnabled()).thenReturn(true);
        when(securityConfig.getUserIdExtractorType()).thenReturn("header");
        when(appConfig.getDatabaseType()).thenReturn(DatabaseType.POSTGRES);
        when(serviceLookup.forBean(IUserIdExtractor.class, "header")).thenReturn(userIdExtractor);
        when(serviceLookup.forBean(IUserContextService.class, "Postgres")).thenReturn(userContextService);

        filter = new UserContextFilter(serviceLookup, appConfig);
        filter.init(null);
    }

    @Test
    void shouldSetUserContextWhenHeaderPresent() throws ServletException, IOException {
        when(userIdExtractor.extractUserId(request)).thenReturn("user-123");
        when(userIdExtractor.extractAdditionalClaims(request)).thenReturn(Map.of("tenant_id", "acme"));

        filter.doFilter(request, response, filterChain);

        verify(userContextService).setUserContext("user-123", Map.of("tenant_id", "acme"));
        verify(filterChain).doFilter(request, response);
        verify(userContextService).clearUserContext();
    }

    @Test
    void shouldSkipSetContextWhenNoUserId() throws ServletException, IOException {
        when(userIdExtractor.extractUserId(request)).thenReturn(null);

        filter.doFilter(request, response, filterChain);

        verify(userContextService, never()).setUserContext(anyString(), any());
        verify(filterChain).doFilter(request, response);
        // clearUserContext still called in finally block
        verify(userContextService).clearUserContext();
    }

    @Test
    void shouldSkipSetContextWhenUserIdBlank() throws ServletException, IOException {
        when(userIdExtractor.extractUserId(request)).thenReturn("   ");

        filter.doFilter(request, response, filterChain);

        verify(userContextService, never()).setUserContext(anyString(), any());
        verify(filterChain).doFilter(request, response);
        verify(userContextService).clearUserContext();
    }

    @Test
    void shouldAlwaysClearContextEvenIfChainThrows() throws ServletException, IOException {
        when(userIdExtractor.extractUserId(request)).thenReturn("user-123");
        when(userIdExtractor.extractAdditionalClaims(request)).thenReturn(Map.of());
        doThrow(new RuntimeException("chain error")).when(filterChain).doFilter(request, response);

        try {
            filter.doFilter(request, response, filterChain);
        } catch (RuntimeException ignored) {
            // expected
        }

        verify(userContextService).clearUserContext();
    }

    @Test
    void shouldSkipWhenUserContextDisabled() throws ServletException, IOException {
        when(securityConfig.isUserContextEnabled()).thenReturn(false);
        filter = new UserContextFilter(serviceLookup, appConfig);
        filter.init(null);

        filter.doFilter(request, response, filterChain);

        verify(userContextService, never()).setUserContext(anyString(), any());
        verify(userContextService, never()).clearUserContext();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldUseDatabaseTypeNameWithoutLowercase() {
        // Verifies the fix: getName() not getName().toLowerCase()
        // "Postgres" must match SupportedDatabaseConstant.POSTGRES
        verify(serviceLookup).forBean(IUserContextService.class, "Postgres");
        verify(serviceLookup, never()).forBean(IUserContextService.class, "postgres");
    }
}
