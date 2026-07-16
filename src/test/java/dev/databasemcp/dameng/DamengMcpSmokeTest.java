package dev.databasemcp.dameng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.diagnostics.DamengDiagnosticDialect;
import dev.databasemcp.diagnostics.DiagnosticDialectProvider;
import dev.databasemcp.diagnostics.ExplainPlanService;
import dev.databasemcp.diagnostics.PostgresExtensionService;
import dev.databasemcp.dialect.DamengDatabaseDialect;
import dev.databasemcp.dialect.DatabaseDialectProvider;
import dev.databasemcp.permission.MetricPermissionEnforcer;
import dev.databasemcp.mcp.DatabaseToolFacade;
import dev.databasemcp.sql.JdbcSqlClient;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.RestrictedSqlGuard;
import dev.databasemcp.sql.SqlAccessMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class DamengMcpSmokeTest {

    @Test
    void 所有Mcp工具可以连接真实达梦实例执行只读冒烟测试() {
        Assumptions.assumeTrue(
            "true".equalsIgnoreCase(env("DAMENG_SMOKE_ENABLED")),
            "未设置 DAMENG_SMOKE_ENABLED=true，跳过真实达梦实例冒烟测试"
        );

        DatabaseMcpProperties properties = damengProperties();
        try (JdbcSqlClient sqlClient = new JdbcSqlClient(properties, new RestrictedSqlGuard())) {
            DatabaseToolFacade facade = facade(properties, sqlClient);
            VisibleTable table = resolveVisibleTable(sqlClient);
            String smokeQuery = configuredQueryOrDefault(table);
            String userId = "dameng-smoke";

            assertSuccessful("execute_sql", facade.executeSql("SELECT 1 AS MCP_SMOKE_VALUE", userId));
            assertSuccessful("list_schemas", facade.listSchemas());
            assertSuccessful("list_objects", facade.listObjects(table.schemaName(), "table"));
            assertSuccessful("get_object_details", facade.getObjectDetails(table.schemaName(), table.tableName(), "table"));
            assertSuccessful("explain_query", facade.explainQuery(smokeQuery, false, List.of(), userId));
            assertSuccessful("get_top_queries", facade.getTopQueries("total_time", 5));
            assertSuccessful("analyze_db_health", facade.analyzeDbHealth("all"));
            assertSuccessful("analyze_workload_indexes", facade.analyzeWorkloadIndexes(10000, "dta"));
            assertSuccessful("analyze_query_indexes", facade.analyzeQueryIndexes(List.of(smokeQuery), 10000, "dta", userId));
        }
    }

    private static DatabaseMcpProperties damengProperties() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.DAMENG);
        properties.setAccessMode(SqlAccessMode.UNRESTRICTED);
        properties.setMaximumPoolSize(2);

        String uri = firstText(env("DAMENG_SMOKE_URI"), env("DATABASE_URI"));
        if (uri != null) {
            properties.setDatabaseUri(uri);
            properties.setDatabaseUsername(firstText(env("DAMENG_SMOKE_USERNAME"), env("DATABASE_USERNAME")));
            properties.setDatabasePassword(firstText(env("DAMENG_SMOKE_PASSWORD"), env("DATABASE_PASSWORD")));
            return properties;
        }

        String host = firstText(env("DAMENG_SMOKE_HOST"), env("DATABASE_HOST"));
        String database = firstText(env("DAMENG_SMOKE_DATABASE"), env("DATABASE_NAME"));
        String username = firstText(env("DAMENG_SMOKE_USERNAME"), env("DATABASE_USERNAME"));
        String password = firstText(env("DAMENG_SMOKE_PASSWORD"), env("DATABASE_PASSWORD"));
        Assumptions.assumeTrue(host != null, "缺少 DAMENG_SMOKE_HOST 或 DATABASE_HOST");
        Assumptions.assumeTrue(database != null, "缺少 DAMENG_SMOKE_DATABASE 或 DATABASE_NAME");
        Assumptions.assumeTrue(username != null, "缺少 DAMENG_SMOKE_USERNAME 或 DATABASE_USERNAME");
        Assumptions.assumeTrue(password != null, "缺少 DAMENG_SMOKE_PASSWORD 或 DATABASE_PASSWORD");

        properties.setDatabaseHost(host);
        properties.setDatabaseName(database);
        properties.setDatabaseUsername(username);
        properties.setDatabasePassword(password);
        properties.setDatabasePort(parsePort(firstText(env("DAMENG_SMOKE_PORT"), env("DATABASE_PORT"))));
        return properties;
    }

    private static DatabaseToolFacade facade(DatabaseMcpProperties properties, JdbcSqlClient sqlClient) {
        DatabaseDialectProvider databaseDialectProvider = new DatabaseDialectProvider(
            properties,
            List.of(new DamengDatabaseDialect(sqlClient))
        );
        DiagnosticDialectProvider diagnosticDialectProvider = new DiagnosticDialectProvider(
            List.of(new DamengDiagnosticDialect(sqlClient)),
            properties
        );
        return new DatabaseToolFacade(
            databaseDialectProvider,
            sqlClient,
            new ExplainPlanService(sqlClient, new PostgresExtensionService(sqlClient), databaseDialectProvider),
            diagnosticDialectProvider,
            mock(MetricPermissionEnforcer.class)
        );
    }

    private static VisibleTable resolveVisibleTable(JdbcSqlClient sqlClient) {
        String configuredSchema = firstText(env("DAMENG_SMOKE_SCHEMA"), env("DATABASE_SCHEMA"));
        String configuredTable = env("DAMENG_SMOKE_TABLE");
        if (configuredSchema != null && configuredTable != null && !configuredTable.isBlank()) {
            return new VisibleTable(configuredSchema, configuredTable);
        }

        QueryResult result = sqlClient.query("""
            SELECT *
            FROM (
                SELECT OWNER AS schema_name, TABLE_NAME AS table_name
                FROM ALL_TABLES
                WHERE OWNER NOT IN ('SYS', 'SYSDBA', 'SYSAUDITOR', 'SYSSSO')
                ORDER BY OWNER, TABLE_NAME
            )
            WHERE ROWNUM <= 1
            """);
        if (result.rows().isEmpty()) {
            result = sqlClient.query("""
                SELECT *
                FROM (
                    SELECT OWNER AS schema_name, TABLE_NAME AS table_name
                    FROM ALL_TABLES
                    ORDER BY OWNER, TABLE_NAME
                )
                WHERE ROWNUM <= 1
                """);
        }
        assertThat(result.rows())
            .as("真实达梦实例中至少需要有一个当前用户可见的表，或通过 DAMENG_SMOKE_SCHEMA/DAMENG_SMOKE_TABLE 指定")
            .isNotEmpty();
        Map<String, Object> row = result.rows().getFirst();
        return new VisibleTable(stringValue(row, "schema_name"), stringValue(row, "table_name"));
    }

    private static String configuredQueryOrDefault(VisibleTable table) {
        String configured = env("DAMENG_SMOKE_QUERY");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return "SELECT * FROM " + quoteIdentifier(table.schemaName()) + "." + quoteIdentifier(table.tableName()) + " WHERE ROWNUM <= 1";
    }

    private static void assertSuccessful(String toolName, String response) {
        assertThat(response)
            .as(toolName + " 应返回文本结果")
            .isNotBlank()
            .doesNotStartWith("错误：");
    }

    private static String stringValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return String.valueOf(entry.getValue());
            }
        }
        throw new IllegalStateException("结果中缺少字段：" + key);
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static Integer parsePort(String rawPort) {
        if (rawPort == null || rawPort.isBlank()) {
            return null;
        }
        return Integer.parseInt(rawPort);
    }

    private static String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    private record VisibleTable(String schemaName, String tableName) {
        private VisibleTable {
            schemaName = schemaName.toUpperCase(Locale.ROOT);
            tableName = tableName.toUpperCase(Locale.ROOT);
        }
    }
}
