package dev.databasemcp.permission;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PermissionContext(PermissionDomain permissionDomain, String userId, Set<MetricScope> metricScopes) {

    public PermissionContext {
        permissionDomain = permissionDomain == null ? PermissionDomain.NONE : permissionDomain;
        userId = userId == null ? "" : userId.trim();
        metricScopes = metricScopes == null ? Set.of() : Set.copyOf(metricScopes);
    }

    public static PermissionContext none() {
        return new PermissionContext(PermissionDomain.NONE, "", Set.of());
    }

    public static PermissionContext metric(String userId, List<Map<String, Object>> metricScopes) {
        return of("metric", userId, metricScopes);
    }

    public static PermissionContext of(String permissionDomain, String userId, List<Map<String, Object>> metricScopes) {
        Set<MetricScope> scopes = new LinkedHashSet<>();
        if (metricScopes != null) {
            for (Map<String, Object> scope : metricScopes) {
                scopes.add(MetricScope.fromMap(scope));
            }
        }
        return new PermissionContext(PermissionDomain.fromExternal(permissionDomain), userId, scopes);
    }

    public boolean isMetricDomain() {
        return permissionDomain.isMetric();
    }

    public boolean hasMetricContext() {
        return isMetricDomain() && !userId.isBlank() && !metricScopes.isEmpty();
    }
}
