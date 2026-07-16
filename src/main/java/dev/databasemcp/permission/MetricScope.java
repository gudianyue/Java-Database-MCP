package dev.databasemcp.permission;

public record MetricScope(String quotaId, String quotaScene) {

    public MetricScope {
        quotaId = requireText(quotaId, "quota_id");
        quotaScene = requireText(quotaScene, "quota_scene");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
