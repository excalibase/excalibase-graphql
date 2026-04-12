package io.github.excalibase.config;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.service.VaultCredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.security.jwt-enabled", havingValue = "true")
@EnableConfigurationProperties(SecurityProperties.class)
public class JwtSecurityConfig {

    @Bean
    public JwtService jwtService(
            SecurityProperties security,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes) {

        SecurityProperties.Auth auth = security.auth();
        if (auth == null) {
            throw new IllegalStateException(
                    "jwt-enabled=true requires app.security.auth.jwks-url or app.security.auth.hmac-secret");
        }

        boolean hasJwks = auth.hasJwksUrl();
        boolean hasHmac = auth.hasHmacSecret();

        if (hasJwks && hasHmac) {
            throw new IllegalStateException(
                    "app.security.auth: set either jwks-url or hmac-secret, not both");
        }
        if (!hasJwks && !hasHmac) {
            throw new IllegalStateException(
                    "jwt-enabled=true requires app.security.auth.jwks-url or app.security.auth.hmac-secret");
        }

        if (hasJwks) {
            return new JwtService(auth.jwksUrl(), ttlMinutes);
        }

        return new JwtService(auth.hmacSecret());
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService) {
        return new JwtAuthFilter(jwtService);
    }

    // Multi-tenant beans — only when provisioning-url is configured
    @Bean
    @ConditionalOnProperty(name = "app.security.multi-tenant.provisioning-url")
    public VaultCredentialService vaultCredentialService(SecurityProperties security) {
        SecurityProperties.MultiTenant mt = security.multiTenant();
        return new VaultCredentialService(mt.provisioningUrl(), mt.provisioningPat());
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.multi-tenant.provisioning-url")
    public DynamicDataSourceManager dynamicDataSourceManager(
            VaultCredentialService vaultCredentialService,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes,
            @Value("${app.hikari.tenant-pool-size:5}") int poolSize) {
        return new DynamicDataSourceManager(vaultCredentialService, ttlMinutes, poolSize);
    }


}
