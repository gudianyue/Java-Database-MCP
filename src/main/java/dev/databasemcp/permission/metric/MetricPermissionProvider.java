package dev.databasemcp.permission.metric;

/** 指标权限 Provider 接口，返回某用户的授权指标范围集合。 */
@FunctionalInterface
public interface MetricPermissionProvider {

    PermissionScope authorizedScopes(String userId);
}
