package dev.databasemcp.config;

import dev.databasemcp.sql.SqlAccessMode;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "database-mcp")
public class DatabaseMcpProperties {

    private DatabaseType databaseType = DatabaseType.POSTGRESQL;
    private String databaseUri;
    private String databaseHost;
    private Integer databasePort;
    private String databaseName;
    private String databaseUsername;
    private String databasePassword;
    private SqlAccessMode accessMode = SqlAccessMode.UNRESTRICTED;
    private int restrictedTimeoutSeconds = 30;
    private int maximumPoolSize = 5;

    public String getDatabaseUri() {
        if (databaseUri == null || databaseUri.isBlank()) {
            return databaseUriEnvironmentVariable();
        }
        return databaseUri;
    }

    protected String databaseUriEnvironmentVariable() {
        return System.getenv("DATABASE_URI");
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
        return databaseType.jdbcPrefix() + host + ":" + getDatabasePort() + "/" + database;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(DatabaseType databaseType) {
        this.databaseType = databaseType == null ? DatabaseType.POSTGRESQL : databaseType;
    }

    public String getDatabaseHost() {
        return databaseHost;
    }

    public void setDatabaseHost(String databaseHost) {
        this.databaseHost = databaseHost;
    }

    public Integer getDatabasePort() {
        return databasePort == null ? databaseType.defaultPort() : databasePort;
    }

    public void setDatabasePort(Integer databasePort) {
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
