package io.github.excalibase.security;

import io.github.excalibase.config.datasource.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String JWT_CLAIMS_ATTR = SecurityConstants.JWT_CLAIMS_ATTR;

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        JwtClaims claims = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                claims = jwtService.verify(token);
                request.setAttribute(JWT_CLAIMS_ATTR, claims);
            } catch (JwtVerificationException _) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"errors\":[{\"message\":\"Invalid or expired token\"}]}");
                return;
            }
        }

        if (claims != null && claims.projectId() != null) {
            try {
                TenantContext.setTenantId(claims.projectId());
                chain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
