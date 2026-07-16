package dev.databasemcp.permission;

import java.util.Set;

public record MetricSqlInspection(
    boolean protectedResource,
    boolean inspectable,
    Set<MetricScope> metricScopes,
    PermissionErrorCode errorCode
) {

    public MetricSqlInspection {
        metricScopes = metricScopes == null ? Set.of() : Set.copyOf(metricScopes);
    }

    public static MetricSqlInspection notProtected() {
        return new MetricSqlInspection(false, true, Set.of(), null);
    }

    public static MetricSqlInspection inspectable(Set<MetricScope> metricScopes) {
        return new MetricSqlInspection(true, true, metricScopes, null);
    }

    public static MetricSqlInspection uninspectable() {
        return new MetricSqlInspection(true, false, Set.of(), PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }
}
