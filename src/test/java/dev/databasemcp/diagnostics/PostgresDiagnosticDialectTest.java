package dev.databasemcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * PostgreSQL 诊断方言单元测试，验证从原 Service 中提取的 PG 逻辑仍然正确。
 */
class PostgresDiagnosticDialectTest {

    @Test
    void databaseTypeReturnsPostgresql() {
        PostgresDiagnosticDialect dialect = createDialect(new RecordingSqlClient());
        assertThat(dialect.databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
    }

    @Test
    void getTopQueriesUsesPg13Columns() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        PostgresDiagnosticDialect dialect = createDialect(sqlClient, true, 16);

        String result = dialect.getTopQueries("mean_time", 5);

        assertThat(sqlClient.lastSql).contains("mean_exec_time").contains("ORDER BY mean_exec_time DESC");
        assertThat(sqlClient.lastParams).containsExactlyElementsOf(List.of(5));
        assertThat(result).contains("单次平均执行时间").contains("SELECT * FROM users");
    }

    @Test
    void getTopQueriesUsesPg12Columns() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        PostgresDiagnosticDialect dialect = createDialect(sqlClient, true, 12);

        dialect.getTopQueries("total_time", 3);

        assertThat(sqlClient.lastSql).contains("total_time").contains("ORDER BY total_time DESC");
    }

    @Test
    void getTopQueriesReturnsInstallMessageWhenExtensionMissing() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        PostgresDiagnosticDialect dialect = createDialect(sqlClient, false, 16);

        String result = dialect.getTopQueries("resources", 10);

        assertThat(result).contains("pg_stat_statements 扩展").contains("CREATE EXTENSION pg_stat_statements");
        assertThat(sqlClient.lastSql).isNull();
    }

    @Test
    void getTopQueriesRejectsInvalidSortCriteria() {
        PostgresDiagnosticDialect dialect = createDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.getTopQueries("calls", 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无效排序条件");
    }

    @Test
    void analyzeHealthDefaultsToAllPgChecks() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        PostgresDiagnosticDialect dialect = createDialect(sqlClient);

        String result = dialect.analyzeHealth(null);

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
    void vacuumHealthUsesPostgresReltoastrelidColumn() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        PostgresDiagnosticDialect dialect = createDialect(sqlClient);

        dialect.analyzeHealth("vacuum");

        assertThat(sqlClient.lastSql)
            .contains("c.reltoastrelid")
            .doesNotContain("c.toastrelid");
    }

    @Test
    void analyzeHealthRejectsInvalidTypes() {
        PostgresDiagnosticDialect dialect = createDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.analyzeHealth("fragmentation"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无效健康检查类型");
    }

    @Test
    void analyzeQueryIndexesWithHypopg() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        PostgresDiagnosticDialect dialect = createDialect(sqlClient, true, true, 16);

        String result = dialect.analyzeQueryIndexes(
            List.of("SELECT * FROM users WHERE email = 'a@example.com'"), 10000, "dta"
        );

        assertThat(result)
            .contains("total_recommendations=1")
            .contains("public.users")
            .contains("email")
            .contains("CREATE INDEX");
    }

    @Test
    void analyzeQueryIndexesReturnsHypopgInstallMessageWhenMissing() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        PostgresDiagnosticDialect dialect = createDialect(sqlClient, true, false, 16);

        String result = dialect.analyzeQueryIndexes(
            List.of("SELECT * FROM users WHERE email = 'a@example.com'"), 10000, "dta"
        );

        assertThat(result).contains("hypopg 扩展").contains("CREATE EXTENSION hypopg");
    }

    // ==================== 辅助类 ====================

    private static PostgresDiagnosticDialect createDialect(RecordingSqlClient sqlClient) {
        return createDialect(sqlClient, true, true, 16);
    }

    private static PostgresDiagnosticDialect createDialect(
        RecordingSqlClient sqlClient, boolean pgStatStatementsInstalled, int majorVersion
    ) {
        return createDialect(sqlClient, pgStatStatementsInstalled, true, majorVersion);
    }

    private static PostgresDiagnosticDialect createDialect(
        RecordingSqlClient sqlClient, boolean pgStatStatementsInstalled, boolean hypopgInstalled, int majorVersion
    ) {
        FakeExtensionService extService = new FakeExtensionService(
            sqlClient, pgStatStatementsInstalled, hypopgInstalled, majorVersion
        );
        return new PostgresDiagnosticDialect(sqlClient, extService);
    }

    private static final class RecordingSqlClient implements SqlClient {
        private String lastSql;
        private List<Object> lastParams;
        private boolean hypopgActive = false;

        @Override
        public QueryResult query(String sql) {
            this.lastSql = sql;
            this.lastParams = List.of();
            return route(sql);
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            this.lastSql = sql;
            this.lastParams = new ArrayList<>(params);
            if (sql.contains("hypopg_create_index")) {
                hypopgActive = true;
            }
            return route(sql);
        }

        private QueryResult route(String sql) {
            if (sql.contains("hypopg_reset")) {
                hypopgActive = false;
                return QueryResult.empty();
            }
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
                return new QueryResult(List.of(row("replica", false)));
            }
            if (sql.contains("pg_stat_replication")) {
                return new QueryResult(List.of(row("count", 0L)));
            }
            if (sql.contains("pg_replication_slots")) {
                return QueryResult.empty();
            }
            if (sql.contains("pg_stat_statements")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", "SELECT * FROM users WHERE email = 'a@example.com'");
                row.put("calls", 10L);
                row.put("rows", 10L);
                row.put("mean_exec_time", 5.0);
                row.put("total_exec_time", 50.0);
                return new QueryResult(List.of(row));
            }
            if (sql.contains("EXPLAIN (FORMAT JSON)")) {
                double cost = hypopgActive ? 40.0 : 100.0;
                return new QueryResult(List.of(row("QUERY PLAN", """
                    [{
                      "Plan": {
                        "Node Type": "Seq Scan",
                        "Relation Name": "users",
                        "Schema": "public",
                        "Filter": "(email = 'a@example.com')",
                        "Total Cost": %s
                      }
                    }]
                    """.formatted(cost))));
            }
            if (sql.contains("information_schema.columns")) {
                return new QueryResult(List.of(row("column_name", "email"), row("column_name", "name")));
            }
            if (sql.contains("pg_index") && sql.contains("unnest(i.indkey)")) {
                return QueryResult.empty();
            }
            if (sql.contains("pg_class c") && sql.contains("pg_stats")) {
                return new QueryResult(List.of(row("row_estimate", 1000.0, "total_width", 64)));
            }
            if (sql.contains("pg_stat_user_tables")) {
                return new QueryResult(List.of(row("last_analyze", java.time.Instant.now())));
            }
            if (sql.contains("pg_constraint") && sql.contains("convalidated")) {
                return QueryResult.empty();
            }
            return QueryResult.empty();
        }
    }

    private static final class FakeExtensionService extends PostgresExtensionService {
        private final boolean pgStatStatementsInstalled;
        private final boolean hypopgInstalled;
        private final int majorVersion;

        private FakeExtensionService(SqlClient sqlClient, boolean pgStatStatementsInstalled, boolean hypopgInstalled, int majorVersion) {
            super(sqlClient);
            this.pgStatStatementsInstalled = pgStatStatementsInstalled;
            this.hypopgInstalled = hypopgInstalled;
            this.majorVersion = majorVersion;
        }

        @Override
        public boolean isExtensionInstalled(String extensionName) {
            return switch (extensionName) {
                case "pg_stat_statements" -> pgStatStatementsInstalled;
                case "hypopg" -> hypopgInstalled;
                default -> false;
            };
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
