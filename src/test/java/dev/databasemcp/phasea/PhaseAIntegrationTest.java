package dev.databasemcp.phasea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.PostgresMcpProperties;
import dev.databasemcp.schema.SchemaIntrospectionService;
import dev.databasemcp.sql.JdbcSqlClient;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.RestrictedSqlGuard;
import dev.databasemcp.sql.SqlAccessMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

class PhaseAIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void startPostgres() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用，跳过 Testcontainers 集成测试");
        POSTGRES.start();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA app");
            statement.execute("CREATE TABLE app.users (id integer primary key, name text not null)");
            statement.execute("CREATE VIEW app.user_names AS SELECT name FROM app.users");
            statement.execute("CREATE SEQUENCE app.user_seq");
            statement.execute("CREATE INDEX users_name_idx ON app.users(name)");
            statement.execute("INSERT INTO app.users VALUES (1, 'Alice')");
        }
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void foundationalToolsWorkAgainstPostgres() {
        try (JdbcSqlClient sqlClient = new JdbcSqlClient(properties(SqlAccessMode.UNRESTRICTED), new RestrictedSqlGuard())) {
            SchemaIntrospectionService schemaService = new SchemaIntrospectionService(sqlClient);

            assertThat(schemaService.listSchemas().rows())
                .anySatisfy(row -> assertThat(row).containsEntry("schema_name", "app"));
            assertThat(schemaService.listObjects("app", "table").rows())
                .anySatisfy(row -> assertThat(row).containsEntry("table_name", "users"));
            assertThat(schemaService.listObjects("app", "view").rows())
                .anySatisfy(row -> assertThat(row).containsEntry("table_name", "user_names"));
            assertThat(schemaService.listObjects("app", "sequence").rows())
                .anySatisfy(row -> assertThat(row).containsEntry("sequence_name", "user_seq"));

            QueryResult details = schemaService.getObjectDetails("app", "users", "table");
            assertThat(details.rows()).hasSize(1);
            assertThat(details.rows().getFirst()).containsEntry("schema", "app").containsEntry("name", "users");

            assertThat(sqlClient.query("SELECT name FROM app.users WHERE id = 1").rows())
                .anySatisfy(row -> assertThat(row).containsEntry("name", "Alice"));
        }
    }

    @Test
    void restrictedModeAllowsReadOnlySqlAndRejectsMutations() {
        try (JdbcSqlClient sqlClient = new JdbcSqlClient(properties(SqlAccessMode.RESTRICTED), new RestrictedSqlGuard())) {
            assertThat(sqlClient.query("SELECT name FROM app.users").rows()).hasSize(1);
            assertThatThrownBy(() -> sqlClient.query("DELETE FROM app.users"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受限模式拒绝 DELETE 语句");
        }
    }

    private static PostgresMcpProperties properties(SqlAccessMode accessMode) {
        PostgresMcpProperties properties = new PostgresMcpProperties();
        properties.setDatabaseUri(POSTGRES.getJdbcUrl() + "?user=" + POSTGRES.getUsername() + "&password=" + POSTGRES.getPassword());
        properties.setAccessMode(accessMode);
        return properties;
    }
}
