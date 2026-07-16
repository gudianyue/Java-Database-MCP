package dev.databasemcp.permission;

import dev.databasemcp.config.DatabaseMcpProperties;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class MetricPermissionConfigurationValidator implements InitializingBean {

    private final DatabaseMcpProperties properties;
    private final ObjectProvider<MetricPermissionProvider> providers;

    MetricPermissionConfigurationValidator(
        DatabaseMcpProperties properties,
        ObjectProvider<MetricPermissionProvider> providers
    ) {
        this.properties = properties;
        this.providers = providers;
    }

    @Override
    public void afterPropertiesSet() {
        DatabaseMcpProperties.PermissionProperties permission = properties.getPermission();
        DatabaseMcpProperties.MetricProperties metric = permission.getMetric();
        DatabaseMcpProperties.CacheProperties cache = metric.getProvider().getCache();
        if (cache.isEnabled()) {
            if (cache.getTtlSeconds() <= 0) {
                throw new IllegalStateException(
                    "database-mcp.permission.metric.provider.cache.ttl-seconds must be greater than zero"
                );
            }
            if (cache.getKeyPrefix() == null || cache.getKeyPrefix().isBlank()) {
                throw new IllegalStateException(
                    "database-mcp.permission.metric.provider.cache.key-prefix must not be blank"
                );
            }
        }
        if (!permission.isEnabled() || !metric.isEnabled()) {
            return;
        }
        requireConfigured(metric.getProtectedTables(), "database-mcp.permission.metric.protected-tables");
        requireConfigured(metric.getMetricColumns(), "database-mcp.permission.metric.metric-columns");
        requireConfigured(metric.getSceneColumns(), "database-mcp.permission.metric.scene-columns");

        int providerCount = providers.orderedStream().toList().size();
        if (providerCount != 1) {
            throw new IllegalStateException("exactly one MetricPermissionProvider must be configured");
        }
    }

    private static void requireConfigured(Set<String> values, String propertyName) {
        if (values == null || values.stream().noneMatch(value -> value != null && !value.isBlank())) {
            throw new IllegalStateException(propertyName + " must be configured when metric permission is enabled");
        }
    }
}
