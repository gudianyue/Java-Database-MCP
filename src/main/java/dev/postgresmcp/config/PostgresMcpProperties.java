package dev.postgresmcp.config;

import dev.postgresmcp.sql.SqlAccessMode;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "postgres-mcp")
public class PostgresMcpProperties {

    private String databaseUri;
    private String databaseHost;
    private int databasePort = 5432;
    private String databaseName;
    private String databaseUsername;
    private String databasePassword;
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

    public String resolvedJdbcUrl() {
        String uri = getDatabaseUri();
        if (hasText(uri)) {
            return uri;
        }
        String host = getDatabaseHost();
        String database = getDatabaseName();
        if (!hasText(host) || !hasText(database)) {
            return "";
        }
        return "jdbc:postgresql://" + host + ":" + getDatabasePort() + "/" + database;
    }

    public String getDatabaseHost() {
        return databaseHost;
    }

    public void setDatabaseHost(String databaseHost) {
        this.databaseHost = databaseHost;
    }

    public int getDatabasePort() {
        return databasePort;
    }

    public void setDatabasePort(int databasePort) {
        this.databasePort = databasePort;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getDatabaseUsername() {
        return databaseUsername;
    }

    public void setDatabaseUsername(String databaseUsername) {
        this.databaseUsername = databaseUsername;
    }

    public String getDatabasePassword() {
        return databasePassword;
    }

    public void setDatabasePassword(String databasePassword) {
        this.databasePassword = databasePassword;
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

    private static boolean hasText(String value) {
        return Optional.ofNullable(value).map(String::isBlank).map(blank -> !blank).orElse(false);
    }
}
