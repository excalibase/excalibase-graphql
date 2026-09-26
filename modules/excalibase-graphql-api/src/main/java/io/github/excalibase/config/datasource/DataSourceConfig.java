package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Replaces Spring Boot's auto-configured DataSource with an AbstractRoutingDataSource.
 * The app starts even if the configured database is unreachable — queries will fail
 * until it is reachable (or after a schema reload).
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource(DataSourceProperties properties,
                                 @Value("${app.security.multi-tenant.provisioning-url:}") String tenantProvisioningUrl,
                                 @Autowired(required = false) DynamicDataSourceManager dataSourceManager) {
        boolean multiTenant = tenantProvisioningUrl != null && !tenantProvisioningUrl.isBlank();
        Map<Object, Object> targets = new HashMap<>();
        boolean hasDefault = registersDefault(properties.getUrl(), properties.getUsername(),
                properties.getPassword(), multiTenant);
        if (hasDefault) {
            HikariDataSource hikari = properties.initializeDataSourceBuilder()
                    .type(HikariDataSource.class)
                    .build();
            hikari.setInitializationFailTimeout(-1);
            targets.put(DynamicRoutingDataSource.DEFAULT_KEY, hikari);
            log.info("DataSource registered: {}", properties.getUrl());
        } else {
            log.info("Multi-tenant mode: no default datasource, every project is served from its own database");
        }

        DynamicRoutingDataSource routing = new DynamicRoutingDataSource(dataSourceManager, hasDefault);
        routing.setTargetDataSources(targets);
        if (hasDefault) {
            routing.setDefaultTargetDataSource(targets.get(DynamicRoutingDataSource.DEFAULT_KEY));
        }
        routing.setLenientFallback(true);
        routing.afterPropertiesSet();
        return routing;
    }

    /**
     * Whether the configured database is registered. There is no built-in one:
     * single-database mode must name its database and credentials, and multi-tenant
     * mode has none, so a URL configured there is refused rather than ignored.
     */
    static boolean registersDefault(String url, String username, String password, boolean multiTenant) {
        boolean hasUrl = url != null && !url.isBlank();
        if (multiTenant) {
            if (hasUrl) {
                throw new IllegalStateException("spring.datasource.url is set, but multi-tenant mode "
                        + "(app.security.multi-tenant.provisioning-url) serves every project from its own "
                        + "database: remove the default datasource");
            }
            return false;
        }
        if (!hasUrl) {
            throw new IllegalStateException("Single-database mode requires spring.datasource.url "
                    + "(SPRING_DATASOURCE_URL) with its username and password; there is no built-in default");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("Single-database mode requires spring.datasource.username "
                    + "(SPRING_DATASOURCE_USERNAME)");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Single-database mode requires spring.datasource.password "
                    + "(SPRING_DATASOURCE_PASSWORD)");
        }
        return true;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
