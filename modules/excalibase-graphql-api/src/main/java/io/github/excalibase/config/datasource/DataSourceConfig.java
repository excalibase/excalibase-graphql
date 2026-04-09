package io.github.excalibase.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * gracefully until a live DataSource is available (or after a schema reload).
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource(DataSourceProperties properties,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false)
                                 DynamicDataSourceManager dataSourceManager) {
        Map<Object, Object> targets = new HashMap<>();
        boolean hasDefault = false;

        if (properties.getUrl() != null && !properties.getUrl().isBlank()) {
            try {
                HikariDataSource hikari = properties.initializeDataSourceBuilder()
                        .type(HikariDataSource.class)
                        .build();
                hikari.setInitializationFailTimeout(-1);
                targets.put(DynamicRoutingDataSource.DEFAULT_KEY, hikari);
                hasDefault = true;
                log.info("DataSource registered: {}", properties.getUrl());
            } catch (Exception e) {
                log.warn("Failed to create DataSource — starting without database. Cause: {}", e.getMessage());
            }
        } else {
            log.warn("No datasource URL configured — starting in multi-tenant only mode");
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
