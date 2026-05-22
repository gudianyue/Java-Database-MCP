package dev.databasemcp.config;

import static org.assertj.core.api.Assertions.assertThat;

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
    void databaseUriTakesPrecedenceOverEnvironmentDatabaseUri() {
        DatabaseMcpProperties properties = new TestDatabaseMcpProperties("jdbc:postgresql://env.example.com:5432/env");
        properties.setDatabaseUri("jdbc:postgresql://db.example.com:5432/app");

        assertThat(properties.getDatabaseUri()).isEqualTo("jdbc:postgresql://db.example.com:5432/app");
        assertThat(properties.resolvedJdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:5432/app");
    }

    @Test
    void environmentDatabaseUriTakesPrecedenceOverSplitConfiguration() {
        DatabaseMcpProperties properties = new TestDatabaseMcpProperties("jdbc:postgresql://env.example.com:5432/env");
        properties.setDatabaseHost("localhost");
        properties.setDatabaseName("ignored");

        assertThat(properties.getDatabaseUri()).isEqualTo("jdbc:postgresql://env.example.com:5432/env");
        assertThat(properties.resolvedJdbcUrl()).isEqualTo("jdbc:postgresql://env.example.com:5432/env");
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
    void returnsBlankJdbcUrlWhenHostOrDatabaseNameAreMissing() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseHost("postgres");

        assertThat(properties.resolvedJdbcUrl()).isBlank();

        properties.setDatabaseHost(null);
        properties.setDatabaseName("appdb");

        assertThat(properties.resolvedJdbcUrl()).isBlank();
    }

    private static class TestDatabaseMcpProperties extends DatabaseMcpProperties {

        private final String databaseUriEnvironmentVariable;

        private TestDatabaseMcpProperties(String databaseUriEnvironmentVariable) {
            this.databaseUriEnvironmentVariable = databaseUriEnvironmentVariable;
        }

        @Override
        protected String databaseUriEnvironmentVariable() {
            return databaseUriEnvironmentVariable;
        }
    }
}
