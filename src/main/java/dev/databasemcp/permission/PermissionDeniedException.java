package dev.databasemcp.permission;

public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(PermissionErrorCode errorCode) {
        super(errorCode.value());
    }
}
