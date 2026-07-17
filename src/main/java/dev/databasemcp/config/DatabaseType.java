package dev.databasemcp.config;

public enum DatabaseType {
    POSTGRESQL(5432, "jdbc:postgresql://"),
    MYSQL(3306, "jdbc:mysql://"),
    DORIS(9030, "jdbc:mysql://"),
    DAMENG(5236, "jdbc:dm://");

    private final int defaultPort;
    private final String jdbcPrefix;

    DatabaseType(int defaultPort, String jdbcPrefix) {
        this.defaultPort = defaultPort;
        this.jdbcPrefix = jdbcPrefix;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String jdbcPrefix() {
        return jdbcPrefix;
    }

}
