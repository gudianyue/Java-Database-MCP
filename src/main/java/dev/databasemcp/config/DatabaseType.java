package dev.databasemcp.config;

import java.util.Arrays;

public enum DatabaseType {
    POSTGRESQL("postgresql", 5432, "jdbc:postgresql://"),
    MYSQL("mysql", 3306, "jdbc:mysql://");

    private final String value;
    private final int defaultPort;
    private final String jdbcPrefix;

    DatabaseType(String value, int defaultPort, String jdbcPrefix) {
        this.value = value;
        this.defaultPort = defaultPort;
        this.jdbcPrefix = jdbcPrefix;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String jdbcPrefix() {
        return jdbcPrefix;
    }

    public static DatabaseType from(String value) {
        if (value == null || value.isBlank()) {
            return POSTGRESQL;
        }
        return Arrays.stream(values())
            .filter(databaseType -> databaseType.value.equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("不支持的数据库类型：" + value));
    }
}
