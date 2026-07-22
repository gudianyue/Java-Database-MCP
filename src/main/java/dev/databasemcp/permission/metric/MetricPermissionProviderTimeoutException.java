package dev.databasemcp.permission.metric;

/** 指标权限 Provider 超时抛出，由 MetricPermissionEnforcer 转译为授权超时。 */
public class MetricPermissionProviderTimeoutException extends RuntimeException {

    public MetricPermissionProviderTimeoutException(String message) {
        super(message);
    }

    public MetricPermissionProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
