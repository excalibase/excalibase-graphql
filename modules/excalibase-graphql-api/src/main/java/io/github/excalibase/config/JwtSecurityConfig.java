package io.github.excalibase.config;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.security.JwtAuthFilter;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.service.VaultCredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires all JWT + vault + dynamic datasource beans when jwt-enabled=true.
 *
 * Putting @ConditionalOnProperty here (on a @Configuration) is the correct
 * GraalVM native image pattern — Spring AOT can reason about it at build time.
 * Individual @Service/@Component classes must NOT carry @ConditionalOnProperty.
 */
@Configuration
@ConditionalOnProperty(name = "app.security.jwt-enabled", havingValue = "true")
public class JwtSecurityConfig {

    @Bean
    public VaultCredentialService vaultCredentialService(
            @Value("${app.security.provisioning-url:}") String provisioningUrl,
            @Value("${app.security.provisioning-pat:}") String provisioningPat) {
        return new VaultCredentialService(provisioningUrl, provisioningPat);
    }

    @Bean
    public JwtService jwtService(
            @Value("${app.security.provisioning-url:}") String provisioningUrl,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes) {
        return new JwtService(provisioningUrl, ttlMinutes);
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService) {
        return new JwtAuthFilter(jwtService);
    }

    @Bean
    public DynamicDataSourceManager dynamicDataSourceManager(
            VaultCredentialService vaultCredentialService,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes,
            @Value("${app.hikari.tenant-pool-size:5}") int poolSize) {
        return new DynamicDataSourceManager(vaultCredentialService, ttlMinutes, poolSize);
    }
}
