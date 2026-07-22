package dev.databasemcp.config;

import dev.databasemcp.sql.SqlAccessMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private PermissionProperties permission = new PermissionProperties();
    private DebugProperties debug = new DebugProperties();

    public String getDatabaseUri() {
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

    public PermissionProperties getPermission() {
        return permission;
    }

    public void setPermission(PermissionProperties permission) {
        this.permission = permission == null ? new PermissionProperties() : permission;
    }

    public DebugProperties getDebug() {
        return debug;
    }

    public void setDebug(DebugProperties debug) {
        this.debug = debug == null ? new DebugProperties() : debug;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static class PermissionProperties {

        private boolean enabled;
        private MetricProperties metric = new MetricProperties();
        private HttpProperties http = new HttpProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public MetricProperties getMetric() {
            return metric;
        }

        public void setMetric(MetricProperties metric) {
            this.metric = metric == null ? new MetricProperties() : metric;
        }

        public HttpProperties getHttp() {
            return http;
        }

        public void setHttp(HttpProperties http) {
            this.http = http == null ? new HttpProperties() : http;
        }
    }

    public static class HttpProperties {

        private String url;
        private Duration timeout = Duration.ofSeconds(3);
        private Map<String, String> headers = Map.of();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public static class MetricProperties {

        private boolean enabled;
        private Set<String> protectedTables = Set.of();
        private Set<String> metricColumns = Set.of();
        private Set<String> sceneColumns = Set.of();
        private ProviderProperties provider = new ProviderProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Set<String> getProtectedTables() {
            return protectedTables;
        }

        public void setProtectedTables(Set<String> protectedTables) {
            this.protectedTables = protectedTables == null ? Set.of() : protectedTables;
        }

        public Set<String> getMetricColumns() {
            return metricColumns;
        }

        public void setMetricColumns(Set<String> metricColumns) {
            this.metricColumns = metricColumns == null ? Set.of() : metricColumns;
        }

        public Set<String> getSceneColumns() {
            return sceneColumns;
        }

        public void setSceneColumns(Set<String> sceneColumns) {
            this.sceneColumns = sceneColumns == null ? Set.of() : sceneColumns;
        }

        public ProviderProperties getProvider() {
            return provider;
        }

        public void setProvider(ProviderProperties provider) {
            this.provider = provider == null ? new ProviderProperties() : provider;
        }
    }

    public static class ProviderProperties {

        private String authorizationQuery;
        private List<String> sceneDelimiters = List.of(",", "，", ";", "|");
        private int timeoutSeconds = 10;
        private CacheProperties cache = new CacheProperties();

        public String getAuthorizationQuery() {
            return authorizationQuery;
        }

        public void setAuthorizationQuery(String authorizationQuery) {
            this.authorizationQuery = authorizationQuery;
        }

        public List<String> getSceneDelimiters() {
            return sceneDelimiters;
        }

        public void setSceneDelimiters(List<String> sceneDelimiters) {
            this.sceneDelimiters = sceneDelimiters == null || sceneDelimiters.isEmpty()
                ? List.of(",", "，", ";", "|")
                : sceneDelimiters;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public CacheProperties getCache() {
            return cache;
        }

        public void setCache(CacheProperties cache) {
            this.cache = cache == null ? new CacheProperties() : cache;
        }
    }

    public static class CacheProperties {

        private boolean enabled;
        private int ttlSeconds = 300;
        private String keyPrefix = "database-mcp:permission:metric:v1:";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    public static class DebugProperties {

        private Http http = new Http();

        public Http getHttp() {
            return http;
        }

        public void setHttp(Http http) {
            this.http = http == null ? new Http() : http;
        }

        public static class Http {

            private boolean enabled;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }
}
