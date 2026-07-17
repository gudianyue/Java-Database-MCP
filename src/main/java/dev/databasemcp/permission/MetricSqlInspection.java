package dev.databasemcp.permission;

import java.util.Set;

record MetricSqlInspection(Status status, Set<MetricScope> metricScopes) {

    MetricSqlInspection {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        metricScopes = metricScopes == null ? Set.of() : Set.copyOf(metricScopes);
        if (status == Status.INSPECTABLE && metricScopes.isEmpty()) {
            throw new IllegalArgumentException("inspectable SQL must contain at least one metric scope");
        }
        if (status != Status.INSPECTABLE && !metricScopes.isEmpty()) {
            throw new IllegalArgumentException("metric scopes are only valid for inspectable SQL");
        }
    }

    static MetricSqlInspection notProtected() {
        return new MetricSqlInspection(Status.NOT_PROTECTED, Set.of());
    }

    static MetricSqlInspection inspectable(Set<MetricScope> metricScopes) {
        return new MetricSqlInspection(Status.INSPECTABLE, metricScopes);
    }

    static MetricSqlInspection uninspectable() {
        return new MetricSqlInspection(Status.UNINSPECTABLE, Set.of());
    }

    boolean protectedResource() {
        return status != Status.NOT_PROTECTED;
    }

    boolean inspectable() {
        return status != Status.UNINSPECTABLE;
    }

    enum Status {
        NOT_PROTECTED,
        INSPECTABLE,
        UNINSPECTABLE
    }
}
