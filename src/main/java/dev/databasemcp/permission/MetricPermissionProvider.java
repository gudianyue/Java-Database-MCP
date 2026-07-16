package dev.databasemcp.permission;

@FunctionalInterface
public interface MetricPermissionProvider {

    PermissionScope authorizedScopes(String userId);
}
