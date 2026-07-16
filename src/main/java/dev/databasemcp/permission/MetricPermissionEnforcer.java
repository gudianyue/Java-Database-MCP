package dev.databasemcp.permission;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MetricPermissionEnforcer {

    private final ConservativeMetricSqlInspector inspector;
    private final MetricPermissionProvider provider;

    @Autowired
    MetricPermissionEnforcer(ConservativeMetricSqlInspector inspector, ObjectProvider<MetricPermissionProvider> providers) {
        this(inspector, providers.getIfUnique());
    }

    MetricPermissionEnforcer(ConservativeMetricSqlInspector inspector, MetricPermissionProvider provider) {
        this.inspector = inspector;
        this.provider = provider;
    }

    public void authorize(String sql, String userId) {
        MetricSqlInspection inspection = inspector.inspect(sql);
        if (!inspection.protectedResource()) {
            return;
        }
        if (!inspection.inspectable()) {
            deny(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
        }
        String effectiveUserId = userId == null ? "" : userId.trim();
        if (effectiveUserId.isBlank()) {
            deny(PermissionErrorCode.PERMISSION_CONTEXT_MISSING);
        }
        if (provider == null) {
            deny(PermissionErrorCode.PERMISSION_PLUGIN_DISABLED_OR_MISSING);
        }
        PermissionScope authorizedScopes;
        try {
            authorizedScopes = provider.authorizedScopes(effectiveUserId);
        } catch (MetricPermissionProviderTimeoutException e) {
            deny(PermissionErrorCode.PERMISSION_PROVIDER_TIMEOUT);
            return;
        } catch (RuntimeException e) {
            deny(PermissionErrorCode.PERMISSION_PROVIDER_UNAVAILABLE);
            return;
        }
        if (authorizedScopes == null || !authorizedScopes.containsAll(inspection.metricScopes())) {
            deny(PermissionErrorCode.PERMISSION_DENIED);
        }
    }

    private static void deny(PermissionErrorCode code) {
        throw new PermissionDeniedException(code);
    }

}
