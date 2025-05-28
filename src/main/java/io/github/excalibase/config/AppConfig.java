package io.github.excalibase.config;

import io.github.excalibase.constant.DatabaseType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "app")
@Component
public class AppConfig {
    private String allowedSchema;
    private DatabaseType databaseType;

    public String getAllowedSchema() {
        return allowedSchema;
    }

    public void setAllowedSchema(String allowedSchema) {
        this.allowedSchema = allowedSchema;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }
}
