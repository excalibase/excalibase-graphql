package io.github.excalibase.security;

import io.github.excalibase.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link JwtAuthFilter} populates {@link RoleContext} so the
 * REST controller (which can't access {@link PostgresRoleResolver} directly
 * for module-dependency reasons) can still emit {@code SET LOCAL ROLE}.
 *
 * <p>{@code RoleContext} is the cross-module bridge — set by the filter, read
 * by both GraphQL and REST data-plane code, cleared in the filter's finally
 * block to keep it thread-pool safe.
 */
class JwtAuthFilterRoleContextTest {

    private static final String VALID_TOKEN = "valid.jwt.token";

    private JwtService jwtService;
    private PostgresRoleResolver resolver;
    private FilterChain chain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        chain = mock(FilterChain.class);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearRoleContext() {
        RoleContext.clear();
    }

    private static SecurityProperties propsWithRoleSwitching() {
        return new SecurityProperties(true, null, null,
                new SecurityProperties.Postgres(
                        new SecurityProperties.Postgres.RoleSwitching(
                                "app_anon",
                                "app_authenticated",
                                "app_service",
                                List.of("app_admin"))));
    }

    private static JwtClaims claimsWithScope(String scope, String role) {
        return new JwtClaims("u1", "p1", "org", "proj", "Org", role, "u@x.com", scope, 0L);
    }

    @Test
    void noAuthHeader_featureEnabled_singleTenantFallback_setsAnonRole() throws Exception {
        resolver = new PostgresRoleResolver(propsWithRoleSwitching());
        var filter = new JwtAuthFilter(jwtService, resolver);
        // No Authorization header — filter should still call resolver and pick anon.

        // Capture RoleContext at the moment chain.doFilter runs.
        String[] captured = new String[1];
        chain = (req, resp) -> captured[0] = RoleContext.getRole();

        filter.doFilter(request, response, chain);

        assertThat(captured[0]).isEqualTo("app_anon");
        // Cleared after filter completes
        assertThat(RoleContext.getRole()).isNull();
    }

    @Test
    void publicScopeJwt_setsAnonRole_andClearsAfter() throws Exception {
        resolver = new PostgresRoleResolver(propsWithRoleSwitching());
        var filter = new JwtAuthFilter(jwtService, resolver);
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
        when(jwtService.verify(anyString())).thenReturn(claimsWithScope("public", "user"));

        String[] captured = new String[1];
        chain = (req, resp) -> captured[0] = RoleContext.getRole();

        filter.doFilter(request, response, chain);

        assertThat(captured[0]).isEqualTo("app_anon");
        assertThat(RoleContext.getRole()).isNull();
    }

    @Test
    void authenticatedScopeJwt_setsAuthenticatedDefault() throws Exception {
        resolver = new PostgresRoleResolver(propsWithRoleSwitching());
        var filter = new JwtAuthFilter(jwtService, resolver);
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
        when(jwtService.verify(anyString())).thenReturn(claimsWithScope("authenticated", "user"));

        String[] captured = new String[1];
        chain = (req, resp) -> captured[0] = RoleContext.getRole();

        filter.doFilter(request, response, chain);

        assertThat(captured[0]).isEqualTo("app_authenticated");
    }

    @Test
    void authenticatedScopeJwt_allowlistedRole_setsThatRole() throws Exception {
        resolver = new PostgresRoleResolver(propsWithRoleSwitching());
        var filter = new JwtAuthFilter(jwtService, resolver);
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
        when(jwtService.verify(anyString())).thenReturn(claimsWithScope("authenticated", "app_admin"));

        String[] captured = new String[1];
        chain = (req, resp) -> captured[0] = RoleContext.getRole();

        filter.doFilter(request, response, chain);

        assertThat(captured[0]).isEqualTo("app_admin");
    }

    @Test
    void roleNotInAllowlist_writes403_doesNotInvokeChain() throws Exception {
        resolver = new PostgresRoleResolver(propsWithRoleSwitching());
        var filter = new JwtAuthFilter(jwtService, resolver);
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
        when(jwtService.verify(anyString())).thenReturn(claimsWithScope("authenticated", "hacker"));

        FilterChain mockChain = mock(FilterChain.class);
        filter.doFilter(request, response, mockChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        verify(mockChain, never()).doFilter(any(), any());
        // No leak after 403
        assertThat(RoleContext.getRole()).isNull();
    }

    @Test
    void invalidJwt_writes401_doesNotPopulateRoleContext() throws Exception {
        resolver = new PostgresRoleResolver(propsWithRoleSwitching());
        var filter = new JwtAuthFilter(jwtService, resolver);
        request.addHeader("Authorization", "Bearer bad.token");
        when(jwtService.verify(anyString())).thenThrow(new JwtVerificationException("bad token"));

        FilterChain mockChain = mock(FilterChain.class);
        filter.doFilter(request, response, mockChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(mockChain, never()).doFilter(any(), any());
        assertThat(RoleContext.getRole()).isNull();
    }

    @Test
    void featureDisabled_doesNotPopulateRoleContext() throws Exception {
        // Feature disabled (no Postgres config) — RoleContext stays null,
        // and existing deploys see no behavioural change.
        resolver = new PostgresRoleResolver((SecurityProperties) null);
        var filter = new JwtAuthFilter(jwtService, resolver);
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
        when(jwtService.verify(anyString())).thenReturn(claimsWithScope("authenticated", "user"));

        String[] captured = new String[1];
        chain = (req, resp) -> captured[0] = RoleContext.getRole();

        filter.doFilter(request, response, chain);

        assertThat(captured[0]).isNull();
        assertThat(RoleContext.getRole()).isNull();
    }

    @Test
    void roleContext_clearedEvenIfChainThrows() throws Exception {
        resolver = new PostgresRoleResolver(propsWithRoleSwitching());
        var filter = new JwtAuthFilter(jwtService, resolver);
        request.addHeader("Authorization", "Bearer " + VALID_TOKEN);
        when(jwtService.verify(anyString())).thenReturn(claimsWithScope("public", "user"));

        FilterChain throwingChain = (req, resp) -> {
            throw new RuntimeException("downstream error");
        };

        try {
            filter.doFilter(request, response, throwingChain);
        } catch (RuntimeException ignored) {
            // expected
        }

        assertThat(RoleContext.getRole())
                .as("RoleContext must be cleared even when downstream throws")
                .isNull();
    }
}
