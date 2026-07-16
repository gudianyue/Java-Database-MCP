package dev.databasemcp.permission;

public enum PermissionErrorCode {
    PERMISSION_DENIED("permission_denied"),
    PERMISSION_CONTEXT_MISSING("permission_context_missing"),
    PERMISSION_SQL_MISMATCH("permission_sql_mismatch"),
    PERMISSION_SQL_UNINSPECTABLE("permission_sql_uninspectable"),
    PERMISSION_PROVIDER_UNAVAILABLE("permission_provider_unavailable"),
    PERMISSION_PROVIDER_TIMEOUT("permission_provider_timeout"),
    PERMISSION_PLUGIN_DISABLED_OR_MISSING("permission_plugin_disabled_or_missing");

    private final String value;

    PermissionErrorCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
