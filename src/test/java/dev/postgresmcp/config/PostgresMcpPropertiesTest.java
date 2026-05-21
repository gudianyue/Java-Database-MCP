package dev.postgresmcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgresMcpPropertiesTest {

    @Test
    void databaseUriTakesPrecedence() {
        PostgresMcpProperties properties = new PostgresMcpProperties();
        properties.setDatabaseUri("jdbc:postgresql://db.example.com:5432/app");
        properties.setDatabaseHost("localhost");
        properties.setDatabaseName("ignored");

        assertThat(properties.resolvedJdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:5432/app");
    }

    @Test
    void buildsJdbcUrlFromHostPortAndDatabaseName() {
        PostgresMcpProperties properties = new PostgresMcpProperties();
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
    void returnsBlankJdbcUrlWhenRequiredPartsAreMissing() {
        PostgresMcpProperties properties = new PostgresMcpProperties();
        properties.setDatabaseHost("postgres");

        assertThat(properties.resolvedJdbcUrl()).isBlank();
    }
}
