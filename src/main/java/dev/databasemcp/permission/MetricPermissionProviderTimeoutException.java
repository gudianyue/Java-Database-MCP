package dev.databasemcp.permission;

public class MetricPermissionProviderTimeoutException extends RuntimeException {

    public MetricPermissionProviderTimeoutException(String message) {
        super(message);
    }

    public MetricPermissionProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
