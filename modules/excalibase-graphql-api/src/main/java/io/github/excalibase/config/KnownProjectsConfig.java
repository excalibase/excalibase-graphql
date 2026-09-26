package io.github.excalibase.config;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.security.KnownProjects;
import io.github.excalibase.security.UnknownProjectCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class KnownProjectsConfig {

    @Bean
    public KnownProjects knownProjects(
            @Value("${app.project-id:}") String projectId,
            @Value("${app.security.multi-tenant.provisioning-url:}") String tenantProvisioningUrl,
            ObjectProvider<DynamicDataSourceManager> dataSourceManager,
            @Value("${app.security.multi-tenant.unknown-project-cache.max-entries:10000}") int unknownMaxEntries,
            @Value("${app.security.multi-tenant.unknown-project-cache.ttl-seconds:30}") long unknownTtlSeconds) {
        UnknownProjectCache unknown = new UnknownProjectCache(unknownMaxEntries,
                Duration.ofSeconds(unknownTtlSeconds), System::currentTimeMillis);
        return KnownProjects.forDeployment(projectId, tenantProvisioningUrl, dataSourceManager.getIfAvailable(), unknown);
    }
}
