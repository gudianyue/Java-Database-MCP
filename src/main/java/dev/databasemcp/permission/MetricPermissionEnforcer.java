package dev.databasemcp.permission;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "database-mcp.permission.metric.enabled", havingValue = "true")
public class MetricPermissionEnforcer implements SqlAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(MetricPermissionEnforcer.class);
    private static final String CONFIGURATION_ERROR = "exactly one MetricPermissionProvider must be configured";

    private final ConservativeMetricSqlInspector inspector;
    private final MetricPermissionProvider provider;

    @Autowired
    MetricPermissionEnforcer(ConservativeMetricSqlInspector inspector, ObjectProvider<MetricPermissionProvider> providers) {
        this(inspector, requireSingleProvider(providers));
    }

    MetricPermissionEnforcer(ConservativeMetricSqlInspector inspector, MetricPermissionProvider provider) {
        this.inspector = inspector;
        if (provider == null) {
            throw new IllegalStateException(CONFIGURATION_ERROR);
        }
        this.provider = provider;
    }

    private static MetricPermissionProvider requireSingleProvider(ObjectProvider<MetricPermissionProvider> providers) {
        List<MetricPermissionProvider> candidates = providers.orderedStream().toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(CONFIGURATION_ERROR);
        }
        return candidates.getFirst();
    }

    @Override
    public boolean isAllowed(String userId, String sql) {
        MetricSqlInspection inspection = inspector.inspect(sql);
        if (!inspection.protectedResource()) {
            return true;
        }
        if (!inspection.inspectable()) {
            return reject("sql_uninspectable", userId, sql);
        }
        String effectiveUserId = userId == null ? "" : userId.trim();
        if (effectiveUserId.isBlank()) {
            return reject("context_missing", userId, sql);
        }
        PermissionScope authorizedScopes;
        try {
            authorizedScopes = provider.authorizedScopes(effectiveUserId);
        } catch (MetricPermissionProviderTimeoutException e) {
            log.error("指标 SQL 授权失败：diagnostic=provider_timeout, userId={}, sql={}", userId, sql);
            throw new SqlAuthorizationTimeoutException("metric permission provider timed out");
        } catch (RuntimeException e) {
            throw unavailable("provider_unavailable", userId, sql);
        }
        if (authorizedScopes == null || !authorizedScopes.containsAll(inspection.metricScopes())) {
            return reject("scope_denied", userId, sql);
        }
        return true;
    }

    private static boolean reject(String diagnostic, String userId, String sql) {
        log.warn("指标 SQL 授权拒绝：diagnostic={}, userId={}, sql={}", diagnostic, userId, sql);
        return false;
    }

    private static IllegalStateException unavailable(String diagnostic, String userId, String sql) {
        log.error("指标 SQL 授权失败：diagnostic={}, userId={}, sql={}", diagnostic, userId, sql);
        return new IllegalStateException("metric permission authorizer unavailable");
    }
}
