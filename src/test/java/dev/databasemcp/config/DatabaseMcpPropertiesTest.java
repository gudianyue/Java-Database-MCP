package dev.databasemcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DatabaseMcpPropertiesTest {

    @Test
    void databaseUriTakesPrecedenceOverSplitConfiguration() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseUri("jdbc:postgresql://db.example.com:5432/app");
        properties.setDatabaseHost("localhost");
        properties.setDatabaseName("ignored");

        assertThat(properties.resolvedJdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:5432/app");
    }

    @Test
    void buildsPostgresqlJdbcUrlFromSplitConfiguration() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.POSTGRESQL);
        properties.setDatabaseHost("postgres");
        properties.setDatabasePort(5544);
        properties.setDatabaseName("appdb");
        properties.setDatabaseUsername("app");
        properties.setDatabasePassword("secret");

        assertThat(properties.resolvedJdbcUrl()).isEqualTo("jdbc:postgresql://postgres:5544/appdb");
        assertThat(properties.getDatabaseUsername()).isEqualTo("app");
        assertThat(properties.getDatabasePassword()).isEqualTo("secret");
    }

    @Test
    void buildsMysqlJdbcUrlWithDefaultPortFromSplitConfiguration() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.MYSQL);
        properties.setDatabaseHost("mysql");
        properties.setDatabaseName("appdb");

        assertThat(properties.getDatabasePort()).isEqualTo(3306);
        assertThat(properties.resolvedJdbcUrl()).isEqualTo("jdbc:mysql://mysql:3306/appdb");
    }

    @Test
    void buildsDamengJdbcUrlWithDefaultPortFromSplitConfiguration() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.DAMENG);
        properties.setDatabaseHost("dameng");
        properties.setDatabaseName("appdb");

        assertThat(properties.getDatabasePort()).isEqualTo(5236);
        assertThat(properties.resolvedJdbcUrl()).isEqualTo("jdbc:dm://dameng:5236/appdb");
    }

    @Test
    void returnsBlankJdbcUrlWhenHostOrDatabaseNameAreMissing() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseHost("postgres");

        assertThat(properties.resolvedJdbcUrl()).isBlank();

        properties.setDatabaseHost(null);
        properties.setDatabaseName("appdb");

        assertThat(properties.resolvedJdbcUrl()).isBlank();
    }

    @Test
    void permissionConfigurationDefaultsToDisabledAndEmptyMetricSets() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();

        assertThat(properties.getPermission().isEnabled()).isFalse();
        assertThat(properties.getPermission().getMetric().isEnabled()).isFalse();
        assertThat(properties.getPermission().getMetric().getProtectedTables()).isEmpty();
        assertThat(properties.getPermission().getMetric().getMetricColumns()).isEmpty();
        assertThat(properties.getPermission().getMetric().getSceneColumns()).isEmpty();
        assertThat(properties.getPermission().getMetric().getProvider().getTimeoutSeconds()).isEqualTo(10);

        DatabaseMcpProperties.CacheProperties cache = properties.getPermission().getMetric().getProvider().getCache();
        assertThat(cache.isEnabled()).isFalse();
        assertThat(cache.getTtlSeconds()).isEqualTo(300);
        assertThat(cache.getKeyPrefix()).isEqualTo("database-mcp:permission:metric:v1:");
    }

    @Test
    void permissionMetricSettersKeepNullAndEmptyCollectionsEmpty() {
        DatabaseMcpProperties.MetricProperties metric = new DatabaseMcpProperties.MetricProperties();

        metric.setProtectedTables(null);
        metric.setMetricColumns(null);
        metric.setSceneColumns(null);

        assertThat(metric.getProtectedTables()).isEmpty();
        assertThat(metric.getMetricColumns()).isEmpty();
        assertThat(metric.getSceneColumns()).isEmpty();

        metric.setMetricColumns(Set.of());
        metric.setSceneColumns(Set.of());

        assertThat(metric.getMetricColumns()).isEmpty();
        assertThat(metric.getSceneColumns()).isEmpty();
    }

}
