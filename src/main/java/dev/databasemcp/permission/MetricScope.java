package dev.databasemcp.permission;

import java.util.Map;

public record MetricScope(String quotaId, String quotaScene) {

    public MetricScope {
        quotaId = requireText(quotaId, "quota_id");
        quotaScene = requireText(quotaScene, "quota_scene");
    }

    public static MetricScope fromMap(Map<String, Object> map) {
        if (map == null) {
            throw new IllegalArgumentException("metric scope is required");
        }
        Object quotaId = map.containsKey("quota_id") ? map.get("quota_id") : map.get("quotaId");
        Object quotaScene = map.containsKey("quota_scene") ? map.get("quota_scene") : map.get("quotaScene");
        return new MetricScope(String.valueOf(quotaId), String.valueOf(quotaScene));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
