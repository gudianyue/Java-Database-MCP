package dev.databasemcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DatabaseHealthService 现为薄路由层，委托到 DiagnosticDialect。
 * PG 逻辑测试在 PostgresDiagnosticDialectTest 中，MySQL 逻辑测试在 MySqlDiagnosticDialectTest 中。
 * 此处只验证路由委托行为。
 */
class DatabaseHealthServiceTest {

    @Test
    void defaultsToAllPgHealthChecks() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        PostgresDiagnosticDialect pgDialect = new PostgresDiagnosticDialect(sqlClient, new FakeExtensionService(sqlClient, true, 16));
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(List.of(pgDialect), properties);
        DatabaseHealthService service = new DatabaseHealthService(provider);

        String result = service.analyze(null);

        assertThat(result)
            .contains("索引健康")
            .contains("连接健康")
            .contains("Vacuum 健康")
            .contains("序列健康")
            .contains("复制健康")
            .contains("缓冲区健康")
            .contains("约束健康");
    }

    @Test
    void supportsCommaSeparatedHealthTypes() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        PostgresDiagnosticDialect pgDialect = new PostgresDiagnosticDialect(sqlClient, new FakeExtensionService(sqlClient, true, 16));
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(List.of(pgDialect), properties);
        DatabaseHealthService service = new DatabaseHealthService(provider);

        String result = service.analyze("connection, buffer");

        assertThat(result).contains("连接健康").contains("缓冲区健康");
        assertThat(result).doesNotContain("索引健康");
    }

    @Test
    void rejectsInvalidHealthTypes() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        PostgresDiagnosticDialect pgDialect = new PostgresDiagnosticDialect(sqlClient, new FakeExtensionService(sqlClient, true, 16));
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(List.of(pgDialect), properties);
        DatabaseHealthService service = new DatabaseHealthService(provider);

        assertThatThrownBy(() -> service.analyze("storage"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无效健康检查类型");
    }

    @Test
    void routesToMySqlDialectForHealthCheck() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.MYSQL);
        MySqlFeatureService featureService = new MySqlFeatureService(sqlClient);
        MySqlDiagnosticDialect mysqlDialect = new MySqlDiagnosticDialect(sqlClient, featureService);
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(List.of(mysqlDialect), properties);
        DatabaseHealthService service = new DatabaseHealthService(provider);

        String result = service.analyze("connection");

        assertThat(result).contains("连接健康");
    }

    private static final class RecordingSqlClient implements SqlClient {
        private final List<String> sqlCalls = new ArrayList<>();

        @Override
        public QueryResult query(String sql) {
            sqlCalls.add(sql);
            return route(sql);
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            sqlCalls.add(sql);
            return route(sql);
        }

        private QueryResult route(String sql) {
            if (sql.contains("NOT i.indisvalid")) {
                return QueryResult.empty();
            }
            if (sql.contains("pg_stat_activity") && sql.contains("idle in transaction")) {
                return new QueryResult(List.of(row("count", 1L)));
            }
            if (sql.contains("pg_stat_activity")) {
                return new QueryResult(List.of(row("count", 12L)));
            }
            if (sql.contains("pg_statio_user_indexes")) {
                return new QueryResult(List.of(row("rate", new BigDecimal("0.981"))));
            }
            if (sql.contains("pg_statio_user_tables")) {
                return new QueryResult(List.of(row("rate", new BigDecimal("0.972"))));
            }
            if (sql.contains("pg_is_in_recovery")) {
                return new QueryResult(List.of(row("replica", false, "replication_lag", 0)));
            }
            if (sql.contains("pg_stat_replication")) {
                return new QueryResult(List.of(row("count", 0L)));
            }
            if (sql.contains("pg_replication_slots")) {
                return QueryResult.empty();
            }
            if (sql.contains("PROCESSLIST") && sql.contains("Sleep")) {
                return new QueryResult(List.of(row("count", 2L)));
            }
            if (sql.contains("PROCESSLIST")) {
                return new QueryResult(List.of(row("count", 15L)));
            }
            if (sql.contains("performance_schema") && sql.contains("global_status")) {
                return new QueryResult(List.of(row("value", "1000")));
            }
            return QueryResult.empty();
        }
    }

    private static final class FakeExtensionService extends PostgresExtensionService {
        private final boolean installed;
        private final int majorVersion;

        private FakeExtensionService(SqlClient sqlClient, boolean installed, int majorVersion) {
            super(sqlClient);
            this.installed = installed;
            this.majorVersion = majorVersion;
        }

        @Override
        public boolean isExtensionInstalled(String extensionName) {
            return installed;
        }

        @Override
        public int postgresMajorVersion() {
            return majorVersion;
        }
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }
}
