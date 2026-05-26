package dev.databasemcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * IndexAdvisorService 现为薄路由层，委托到 DiagnosticDialect。
 * PG 逻辑测试在 PostgresDiagnosticDialectTest 中，MySQL 逻辑测试在 MySqlDiagnosticDialectTest 中。
 * 此处只验证路由委托行为。
 */
class IndexAdvisorServiceTest {

    @Test
    void delegatesToPgDialectForExplicitQueries() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        FakeExtensionService extService = new FakeExtensionService(sqlClient, true, true, 16);
        PostgresDiagnosticDialect pgDialect = new PostgresDiagnosticDialect(sqlClient, extService);
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(List.of(pgDialect), properties);
        IndexAdvisorService service = new IndexAdvisorService(provider);

        String result = service.analyzeQueryIndexes(List.of("SELECT * FROM users WHERE email = 'a@example.com'"), 10000, "dta");

        assertThat(result)
            .contains("total_recommendations=1")
            .contains("public.users")
            .contains("email")
            .contains("CREATE INDEX");
    }

    @Test
    void rejectsTooManyQueries() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        PostgresDiagnosticDialect pgDialect = new PostgresDiagnosticDialect(sqlClient, new FakeExtensionService(sqlClient, true, true, 16));
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(List.of(pgDialect), properties);
        IndexAdvisorService service = new IndexAdvisorService(provider);

        assertThatThrownBy(() -> service.analyzeQueryIndexes(
            List.of("SELECT 1", "SELECT 2", "SELECT 3", "SELECT 4", "SELECT 5",
                     "SELECT 6", "SELECT 7", "SELECT 8", "SELECT 9", "SELECT 10", "SELECT 11"),
            10000, "dta"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("最多 10 条");
    }

    @Test
    void routesToMySqlDialectForWorkloadIndexes() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.MYSQL);
        MySqlFeatureService featureService = new MySqlFeatureService(sqlClient);
        MySqlDiagnosticDialect mysqlDialect = new MySqlDiagnosticDialect(sqlClient, featureService);
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(List.of(mysqlDialect), properties);
        IndexAdvisorService service = new IndexAdvisorService(provider);

        String result = service.analyzeQueryIndexes(List.of("SELECT * FROM users WHERE email = 'a@example.com'"), 10000, "dta");

        assertThat(result).isNotNull();
    }

    private static final class RecordingSqlClient implements SqlClient {
        private final List<String> sqlCalls = new ArrayList<>();
        private boolean hypopgActive = false;

        @Override
        public QueryResult query(String sql) {
            sqlCalls.add(sql);
            return route(sql);
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            sqlCalls.add(sql);
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
            if (sql.contains("pg_stat_user_tables")) {
                return new QueryResult(List.of(row("last_analyze", Instant.now())));
            }
            if (sql.contains("FROM pg_stat_statements")) {
                return new QueryResult(List.of(row(
                    "query", "SELECT * FROM users WHERE email = 'a@example.com'",
                    "calls", 100L,
                    "avg_exec_time", 20.0
                )));
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
            if (sql.contains("EXPLAIN FORMAT=JSON")) {
                return new QueryResult(List.of(row("EXPLAIN", """
                    {"query_block": {"cost_info": {"query_cost": "100.00"}, "table": {"table_name": "users", "access_type": "ALL", "attached_condition": "(`users`.`email` = 'a@example.com')"}}}
                    """)));
            }
            if (sql.contains("information_schema.columns")) {
                return new QueryResult(List.of(row("column_name", "email"), row("column_name", "name")));
            }
            if (sql.contains("COLUMN_NAME") && sql.contains("information_schema.COLUMNS")) {
                return new QueryResult(List.of(row("COLUMN_NAME", "email")));
            }
            if (sql.contains("pg_index") && sql.contains("unnest(i.indkey)")) {
                return QueryResult.empty();
            }
            if (sql.contains("pg_class c") && sql.contains("pg_stats")) {
                return new QueryResult(List.of(row("row_estimate", 1000.0, "total_width", 64)));
            }
            if (sql.contains("TABLE_ROWS") && sql.contains("information_schema.TABLES")) {
                return new QueryResult(List.of(row("row_estimate", 1000, "avg_row_length", 64)));
            }
            if (sql.contains("STATISTICS") && sql.contains("SEQ_IN_INDEX")) {
                return QueryResult.empty();
            }
            if (sql.contains("events_statements_summary_by_digest")) {
                return new QueryResult(List.of(row(
                    "query", "SELECT `users`.`id` , `users`.`email` FROM `users` WHERE `users`.`email` = ?",
                    "calls", 100L,
                    "avg_exec_time_sec", 20.0
                )));
            }
            if (sql.contains("@@performance_schema")) {
                return new QueryResult(List.of(row("enabled", 1)));
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
