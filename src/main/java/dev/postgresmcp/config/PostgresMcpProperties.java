package dev.postgresmcp.config;

import dev.postgresmcp.sql.SqlAccessMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "postgres-mcp")
public class PostgresMcpProperties {

    private String databaseUri;
    private SqlAccessMode accessMode = SqlAccessMode.UNRESTRICTED;
    private int restrictedTimeoutSeconds = 30;
    private int maximumPoolSize = 5;

    public String getDatabaseUri() {
        if (databaseUri == null || databaseUri.isBlank()) {
            return System.getenv("DATABASE_URI");
        }
        return databaseUri;
    }

    public void setDatabaseUri(String databaseUri) {
        this.databaseUri = databaseUri;
    }

    public SqlAccessMode getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(SqlAccessMode accessMode) {
        this.accessMode = accessMode;
    }

    public int getRestrictedTimeoutSeconds() {
        return restrictedTimeoutSeconds;
    }

    public void setRestrictedTimeoutSeconds(int restrictedTimeoutSeconds) {
        this.restrictedTimeoutSeconds = restrictedTimeoutSeconds;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }
}
