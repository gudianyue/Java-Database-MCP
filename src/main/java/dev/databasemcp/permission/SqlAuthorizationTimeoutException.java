package dev.databasemcp.permission;

public class SqlAuthorizationTimeoutException extends RuntimeException {

    public SqlAuthorizationTimeoutException(String message) {
        super(message);
    }

    public SqlAuthorizationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
