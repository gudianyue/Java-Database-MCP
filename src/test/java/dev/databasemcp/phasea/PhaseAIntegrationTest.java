package dev.databasemcp.phasea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.dialect.DatabaseDialect;
import dev.databasemcp.dialect.DatabaseDialectProvider;
import dev.databasemcp.schema.SchemaIntrospectionService;
import dev.databasemcp.sql.JdbcSqlClient;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.RestrictedSqlGuard;
import dev.databasemcp.sql.SqlAccessMode;
import java.util.List;
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
            SchemaIntrospectionService schemaService = new SchemaIntrospectionService(
                new DatabaseDialectProvider(properties(SqlAccessMode.UNRESTRICTED), List.of(new IntegrationPostgresDialect(sqlClient)))
            );

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

    private static DatabaseMcpProperties properties(SqlAccessMode accessMode) {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseUri(POSTGRES.getJdbcUrl() + "?user=" + POSTGRES.getUsername() + "&password=" + POSTGRES.getPassword());
        properties.setAccessMode(accessMode);
        return properties;
    }

    private record IntegrationPostgresDialect(JdbcSqlClient sqlClient) implements DatabaseDialect {

        @Override
        public DatabaseType databaseType() {
            return DatabaseType.POSTGRESQL;
        }

        @Override
        public QueryResult listSchemas() {
            return sqlClient.query("""
                SELECT
                    schema_name,
                    schema_owner,
                    CASE
                        WHEN schema_name LIKE 'pg_%' THEN 'System Schema'
                        WHEN schema_name = 'information_schema' THEN 'System Information Schema'
                        ELSE 'User Schema'
                    END as schema_type
                FROM information_schema.schemata
                ORDER BY schema_type, schema_name
                """);
        }

        @Override
        public QueryResult listObjects(String schemaName, String objectType) {
            return switch (objectType) {
                case "table" -> sqlClient.query("""
                    SELECT table_schema, table_name, table_type
                    FROM information_schema.tables
                    WHERE table_schema = ? AND table_type = ?
                    ORDER BY table_name
                    """, List.of(schemaName, "BASE TABLE"));
                case "view" -> sqlClient.query("""
                    SELECT table_schema, table_name, table_type
                    FROM information_schema.tables
                    WHERE table_schema = ? AND table_type = ?
                    ORDER BY table_name
                    """, List.of(schemaName, "VIEW"));
                case "sequence" -> sqlClient.query("""
                    SELECT sequence_schema, sequence_name, data_type
                    FROM information_schema.sequences
                    WHERE sequence_schema = ?
                    ORDER BY sequence_name
                    """, List.of(schemaName));
                case "extension" -> sqlClient.query("""
                    SELECT extname, extversion, extrelocatable
                    FROM pg_extension
                    ORDER BY extname
                    """);
                default -> throw new IllegalArgumentException("不支持的对象类型：" + objectType);
            };
        }

        @Override
        public QueryResult getObjectDetails(String schemaName, String objectName, String objectType) {
            return switch (objectType) {
                case "table", "view" -> sqlClient.query("""
                    WITH columns AS (
                        SELECT json_agg(json_build_object(
                            'column', column_name,
                            'data_type', data_type,
                            'is_nullable', is_nullable,
                            'default', column_default
                        ) ORDER BY ordinal_position) AS columns
                        FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = ?
                    ),
                    constraints AS (
                        SELECT json_agg(json_build_object(
                            'name', constraint_name,
                            'type', constraint_type,
                            'columns', columns
                        )) AS constraints
                        FROM (
                            SELECT
                                tc.constraint_name,
                                tc.constraint_type,
                                json_agg(kcu.column_name ORDER BY kcu.ordinal_position) FILTER (WHERE kcu.column_name IS NOT NULL) AS columns
                            FROM information_schema.table_constraints tc
                            LEFT JOIN information_schema.key_column_usage kcu
                                ON tc.constraint_name = kcu.constraint_name
                               AND tc.table_schema = kcu.table_schema
                            WHERE tc.table_schema = ? AND tc.table_name = ?
                            GROUP BY tc.constraint_name, tc.constraint_type
                        ) grouped_constraints
                    ),
                    indexes AS (
                        SELECT json_agg(json_build_object('name', indexname, 'definition', indexdef)) AS indexes
                        FROM pg_indexes
                        WHERE schemaname = ? AND tablename = ?
                    )
                    SELECT
                        ? AS schema,
                        ? AS name,
                        COALESCE(columns.columns, '[]'::json) AS columns,
                        COALESCE(constraints.constraints, '[]'::json) AS constraints,
                        COALESCE(indexes.indexes, '[]'::json) AS indexes
                    FROM columns, constraints, indexes
                    """, List.of(schemaName, objectName, schemaName, objectName, schemaName, objectName, schemaName, objectName));
                case "sequence" -> sqlClient.query("""
                    SELECT sequence_schema, sequence_name, data_type, start_value, increment
                    FROM information_schema.sequences
                    WHERE sequence_schema = ? AND sequence_name = ?
                    """, List.of(schemaName, objectName));
                case "extension" -> sqlClient.query("""
                    SELECT extname, extversion, extrelocatable
                    FROM pg_extension
                    WHERE extname = ?
                    """, List.of(objectName));
                default -> throw new IllegalArgumentException("不支持的对象类型：" + objectType);
            };
        }

        @Override
        public QueryResult explain(String sql) {
            return sqlClient.query("EXPLAIN " + sql);
        }
    }
}
