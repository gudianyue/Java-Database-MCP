package dev.databasemcp.permission;

/** SQL 授权拒绝时抛出，携带通用错误码，不含内部诊断细节。 */
public class PermissionDeniedException extends RuntimeException {

    private final PermissionErrorCode errorCode;

    public PermissionDeniedException(PermissionErrorCode errorCode) {
        super(errorCode.value());
        this.errorCode = errorCode;
    }

    public PermissionErrorCode errorCode() {
        return errorCode;
    }
}
