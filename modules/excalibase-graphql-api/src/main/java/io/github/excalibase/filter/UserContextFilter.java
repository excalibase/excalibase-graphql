package io.github.excalibase.filter;

import io.github.excalibase.config.AppConfig;
import io.github.excalibase.extractor.IUserIdExtractor;
import io.github.excalibase.service.IUserContextService;
import io.github.excalibase.service.ServiceLookup;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Filter that sets user context for RLS (Row Level Security) policies.
 *
 * Flow per request:
 *   1. IUserIdExtractor extracts user ID (and optional claims) from the request
 *   2. IUserContextService sets PostgreSQL session variables
 *   3. GraphQL resolvers run — RLS policies read current_setting('request.user_id', true)
 *   4. Session variables are cleared after request (prevents connection pool leakage)
 *
 * Configuration:
 * <pre>
 * app:
 *   security:
 *     user-context-enabled: true
 *     user-id-extractor-type: header   # "header" or "jwt"
 *     user-id-header: X-User-Id        # only used with "header" extractor
 * </pre>
 *
 * Header extractor example:
 *   X-User-Id: user-123
 *   X-Claim-tenant_id: acme-corp
 *
 * JWT extractor example:
 *   Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
 *   (extracts "sub" as user_id, other claims as request.jwt.*)
 *
 * RLS policy example:
 *   CREATE POLICY user_isolation ON orders
 *   FOR ALL USING (user_id = current_setting('request.user_id', true));
 */
@Component
@Order(1)
public class UserContextFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(UserContextFilter.class);

    private final ServiceLookup serviceLookup;
    private final AppConfig appConfig;

    private IUserIdExtractor userIdExtractor;
    private IUserContextService userContextService;

    public UserContextFilter(ServiceLookup serviceLookup, AppConfig appConfig) {
        this.serviceLookup = serviceLookup;
        this.appConfig = appConfig;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        if (!appConfig.getSecurity().isUserContextEnabled()) {
            log.info("User context filter is disabled");
            return;
        }

        try {
            String extractorType = appConfig.getSecurity().getUserIdExtractorType();
            this.userIdExtractor = serviceLookup.forBean(IUserIdExtractor.class, extractorType);
            log.info("User context filter initialized with extractor type: {}", extractorType);

            String dbType = appConfig.getDatabaseType().getName();
            this.userContextService = serviceLookup.forBean(IUserContextService.class, dbType);
            log.info("User context service initialized for database: {}", dbType);

        } catch (Exception e) {
            log.error("Failed to initialize user context filter: {}", e.getMessage());
            throw new ServletException("User context filter initialization failed", e);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!appConfig.getSecurity().isUserContextEnabled()
                || userIdExtractor == null
                || userContextService == null) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        try {
            String userId = userIdExtractor.extractUserId(httpRequest);

            if (userId != null && !userId.trim().isEmpty()) {
                Map<String, String> claims = userIdExtractor.extractAdditionalClaims(httpRequest);
                userContextService.setUserContext(userId, claims);
                log.debug("Set user context: userId={}, claims={}", userId, claims.keySet());
            } else {
                log.debug("No user ID extracted — skipping user context");
            }

            chain.doFilter(request, response);

        } finally {
            // Always clear — prevents session variable leakage across connection pool reuse
            userContextService.clearUserContext();
        }
    }

    @Override
    public void destroy() {
        log.info("User context filter destroyed");
    }
}
