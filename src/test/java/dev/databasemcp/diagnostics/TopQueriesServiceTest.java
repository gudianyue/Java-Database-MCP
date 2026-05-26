package dev.databasemcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TopQueriesService 现为薄路由层，委托到 DiagnosticDialect。
 * PG 逻辑测试在 PostgresDiagnosticDialectTest 中，MySQL 逻辑测试在 MySqlDiagnosticDialectTest 中。
 * 此处只验证路由委托行为。
 */
class TopQueriesServiceTest {

    @Test
    void delegatesToDiagnosticDialect() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        PostgresExtensionService extService = new FakeExtensionService(sqlClient, true, 16);
        PostgresDiagnosticDialect pgDialect = new PostgresDiagnosticDialect(sqlClient, extService);
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(List.of(pgDialect), properties);
        TopQueriesService service = new TopQueriesService(provider);

        String result = service.getTopQueries("mean_time", 5);

        assertThat(sqlClient.lastSql).contains("mean_exec_time").contains("ORDER BY mean_exec_time DESC");
        assertThat(result).contains("单次平均执行时间").contains("SELECT * FROM users");
    }

    private static final class RecordingSqlClient implements SqlClient {
        private String lastSql;
        private List<Object> lastParams;

        @Override
        public QueryResult query(String sql) {
            this.lastSql = sql;
            this.lastParams = List.of();
            return rows();
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            this.lastSql = sql;
            this.lastParams = new ArrayList<>(params);
            return rows();
        }

        private static QueryResult rows() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("query", "SELECT * FROM users");
            row.put("calls", 10L);
            row.put("rows", 10L);
            return new QueryResult(List.of(row));
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
}
