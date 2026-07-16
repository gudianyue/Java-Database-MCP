package dev.databasemcp.permission;

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
