package dev.databasemcp.permission;

/** SQL 授权器超时抛出，由执行器转译为通用超时错误码。 */
public class SqlAuthorizationTimeoutException extends RuntimeException {

    public SqlAuthorizationTimeoutException(String message) {
        super(message);
    }

    public SqlAuthorizationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
