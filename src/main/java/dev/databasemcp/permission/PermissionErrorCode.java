package dev.databasemcp.permission;

public enum PermissionErrorCode {
    PERMISSION_DENIED("permission_denied"),
    PERMISSION_CONTEXT_MISSING("permission_context_missing"),
    PERMISSION_AUTHORIZER_UNAVAILABLE("permission_authorizer_unavailable"),
    PERMISSION_AUTHORIZER_TIMEOUT("permission_authorizer_timeout");

    private final String value;

    PermissionErrorCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
