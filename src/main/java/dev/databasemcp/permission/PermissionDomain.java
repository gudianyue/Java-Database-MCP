package dev.databasemcp.permission;

import java.util.Locale;

public enum PermissionDomain {
    NONE("none"),
    METRIC("metric");

    private final String value;

    PermissionDomain(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isMetric() {
        return this == METRIC;
    }

    public static PermissionDomain fromExternal(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PermissionDomain domain : values()) {
            if (domain.value.equals(normalized)) {
                return domain;
            }
        }
        return NONE;
    }
}
