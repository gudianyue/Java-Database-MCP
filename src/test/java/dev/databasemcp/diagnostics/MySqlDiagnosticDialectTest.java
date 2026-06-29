package dev.databasemcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MySqlDiagnosticDialectTest {

    @Test
    void databaseTypeReturnsMysql() {
        MySqlDiagnosticDialect dialect = createDialect(new RecordingSqlClient());
        assertThat(dialect.databaseType()).isEqualTo(dev.databasemcp.config.DatabaseType.MYSQL);
    }

    @Test
    void getTopQueriesReturnsInstallMessageWhenPerformanceSchemaDisabled() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        sqlClient.perfSchemaEnabled = false;
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        String result = dialect.getTopQueries("total_time", 10);

        assertThat(result).contains("performance_schema").contains("performance_schema = ON");
    }

    @Test
    void getTopQueriesByTotalTime() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        String result = dialect.getTopQueries("total_time", 5);

        assertThat(sqlClient.lastSql).contains("events_statements_summary_by_digest")
            .contains("ORDER BY SUM_TIMER_WAIT DESC")
            .contains("1000000000000");
        assertThat(sqlClient.lastParams).containsExactlyElementsOf(List.of(5));
        assertThat(result).contains("总执行时间").contains("SELECT * FROM users");
    }

    @Test
    void getTopQueriesByMeanTime() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        String result = dialect.getTopQueries("mean_time", 5);

        assertThat(sqlClient.lastSql).contains("ORDER BY SUM_TIMER_WAIT / NULLIF(COUNT_STAR, 0) DESC");
        assertThat(result).contains("单次平均执行时间");
    }

    @Test
    void analyzeWorkloadIndexesUsesSecondsForPerformanceSchemaTimers() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        dialect.analyzeWorkloadIndexes(10000, "dta");

        assertThat(sqlClient.sqlCalls)
            .anySatisfy(sql -> assertThat(sql)
                .contains("events_statements_summary_by_digest")
                .contains("1000000000000"));
    }

    @Test
    void getTopQueriesRejectsInvalidSortCriteria() {
        MySqlDiagnosticDialect dialect = createDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.getTopQueries("resources", 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无效排序条件");
    }

    @Test
    void getTopQueriesRejectsLimitLessThanOne() {
        MySqlDiagnosticDialect dialect = createDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.getTopQueries("total_time", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit 必须大于 0");
    }

    @Test
    void analyzeHealthDefaultsToAllChecks() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        String result = dialect.analyzeHealth(null);

        assertThat(result)
            .contains("索引健康")
            .contains("连接健康")
            .contains("碎片健康")
            .contains("自增列健康")
            .contains("复制健康")
            .contains("缓冲池健康")
            .contains("约束健康");
    }

    @Test
    void analyzeHealthRejectsInvalidTypes() {
        MySqlDiagnosticDialect dialect = createDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.analyzeHealth("vacuum"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无效健康检查类型");
    }

    @Test
    void connectionHealthReportsLongSleepConnections() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        sqlClient.processListTotal = 50L;
        sqlClient.processListIdleLong = 120L;
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        String result = dialect.analyzeHealth("connection");

        assertThat(sqlClient.sqlCalls)
            .anySatisfy(sql -> assertThat(sql).contains("COMMAND = 'Sleep'").doesNotContain("STATE = 'Sleep'"));
        assertThat(result).contains("长时间 Sleep 连接数过高：120");
    }

    @Test
    void connectionHealthReportsNormalStatus() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        sqlClient.processListTotal = 20L;
        sqlClient.processListIdleLong = 2L;
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        String result = dialect.analyzeHealth("connection");

        assertThat(result).contains("连接健康：20").contains("2 个长时间 Sleep");
    }

    @Test
    void bufferHealthReportsHitRate() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        sqlClient.bufferPoolReads = "100";
        sqlClient.bufferPoolRequests = "10000";
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        String result = dialect.analyzeHealth("buffer");

        assertThat(result).contains("InnoDB 缓冲池命中率：99.0%").contains("高于 95.0%");
    }

    @Test
    void largeIndexHealthUsesInnodbIndexStatsSizeRows() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        dialect.analyzeHealth("index");

        assertThat(sqlClient.sqlCalls)
            .anySatisfy(sql -> assertThat(sql)
                .contains("mysql.innodb_index_stats")
                .contains("DATABASE_NAME AS schema_name")
                .contains("STAT_VALUE AS size_pages")
                .doesNotContain("CLUSTED_INDEX_SIZE")
                .doesNotContain("SECONDARY_INDEX_SIZE"));
    }

    @Test
    void autoIncrementHealthChecksNarrowIntegerTypesBeforePlainInt() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        dialect.analyzeHealth("auto_increment");

        assertThat(sqlClient.lastSql).contains("WHEN c.COLUMN_TYPE LIKE '%%mediumint unsigned%%' THEN '16777215'");
        assertThat(sqlClient.lastSql.indexOf("%%mediumint unsigned%%"))
            .isLessThan(sqlClient.lastSql.indexOf("%%int unsigned%%"));
        assertThat(sqlClient.lastSql.indexOf("%%tinyint unsigned%%"))
            .isLessThan(sqlClient.lastSql.indexOf("%%int unsigned%%"));
    }

    @Test
    void analyzeQueryIndexesWithRuleEngine() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        MySqlDiagnosticDialect dialect = createDialect(sqlClient);

        String result = dialect.analyzeQueryIndexes(
            List.of("SELECT * FROM orders WHERE status = 'ACTIVE'"), 10000, "dta"
        );

        assertThat(result).contains("recommendations").contains("method=rule_engine");
        assertThat(result).contains("`orders`").contains("`status`");
    }

    @Test
    void analyzeQueryIndexesRejectsTooManyQueries() {
        MySqlDiagnosticDialect dialect = createDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.analyzeQueryIndexes(
            List.of("SELECT 1", "SELECT 2", "SELECT 3", "SELECT 4", "SELECT 5",
                     "SELECT 6", "SELECT 7", "SELECT 8", "SELECT 9", "SELECT 10", "SELECT 11"),
            10000, "dta"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("最多 10 条");
    }

    @Test
    void analyzeQueryIndexesRejectsEmptyQueryList() {
        MySqlDiagnosticDialect dialect = createDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.analyzeQueryIndexes(List.of(), 10000, "dta"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("非空");
    }

    @Test
    void analyzeQueryIndexesDefersLlmMethod() {
        MySqlDiagnosticDialect dialect = createDialect(new RecordingSqlClient());

        String result = dialect.analyzeQueryIndexes(List.of("SELECT * FROM users"), 10000, "llm");

        assertThat(result).contains("LLM 索引优化方法").contains("规则引擎评分");
    }

    // ==================== 辅助类 ====================

    private static MySqlDiagnosticDialect createDialect(RecordingSqlClient sqlClient) {
        MySqlFeatureService featureService = new MySqlFeatureService(sqlClient);
        return new MySqlDiagnosticDialect(sqlClient, featureService);
    }

    private static final class RecordingSqlClient implements SqlClient {
        String lastSql;
        List<Object> lastParams;
        List<String> sqlCalls = new ArrayList<>();
        boolean perfSchemaEnabled = true;
        long processListTotal = 12L;
        long processListIdleLong = 1L;
        String bufferPoolReads = "100";
        String bufferPoolRequests = "5000";

        @Override
        public QueryResult query(String sql) {
            this.lastSql = sql;
            this.lastParams = List.of();
            this.sqlCalls.add(sql);
            return route(sql);
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            this.lastSql = sql;
            this.lastParams = new ArrayList<>(params);
            this.sqlCalls.add(sql);
            return route(sql);
        }

        private QueryResult route(String sql) {
            if (sql.contains("@@performance_schema")) {
                return new QueryResult(List.of(row("enabled", perfSchemaEnabled ? 1 : 0)));
            }
            if (sql.contains("events_statements_summary_by_digest")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", "SELECT * FROM users WHERE status = ?");
                row.put("calls", 100L);
                row.put("total_exec_time_sec", 5.0);
                row.put("mean_exec_time_sec", 0.05);
                row.put("rows_examined", 1000L);
                row.put("rows_sent", 50L);
                row.put("errors", 0L);
                return new QueryResult(List.of(row));
            }
            if (sql.contains("PROCESSLIST") && sql.contains("COMMAND = 'Sleep'") && sql.contains("TIME > 60")) {
                return new QueryResult(List.of(row("count", processListIdleLong)));
            }
            if (sql.contains("PROCESSLIST") && sql.contains("Binlog Dump")) {
                return new QueryResult(List.of(row("count", 2L)));
            }
            if (sql.contains("PROCESSLIST")) {
                return new QueryResult(List.of(row("count", processListTotal)));
            }
            if (sql.contains("global_status") && sql.contains("Innodb_buffer_pool_reads")) {
                return new QueryResult(List.of(row("value", bufferPoolReads)));
            }
            if (sql.contains("global_status") && sql.contains("Innodb_buffer_pool_read_requests")) {
                return new QueryResult(List.of(row("value", bufferPoolRequests)));
            }
            if (sql.contains("global_variables") && sql.contains("super_read_only")) {
                return new QueryResult(List.of(row("replica", 0)));
            }
            if (sql.contains("@@read_only")) {
                return new QueryResult(List.of(row("replica", 0)));
            }
            if (sql.contains("SHOW REPLICA STATUS") || sql.contains("SHOW SLAVE STATUS")) {
                return QueryResult.empty();
            }
            if (sql.contains("STATISTICS") && sql.contains("duplicate_index")) {
                return QueryResult.empty();
            }
            if (sql.contains("innodb_index_stats") && sql.contains("SIZE")) {
                return QueryResult.empty();
            }
            if (sql.contains("table_io_waits_summary_by_index_usage")) {
                return QueryResult.empty();
            }
            if (sql.contains("information_schema.TABLES") && sql.contains("DATA_FREE")) {
                return QueryResult.empty();
            }
            if (sql.contains("REFERENTIAL_CONSTRAINTS")) {
                return QueryResult.empty();
            }
            if (sql.contains("AUTO_INCREMENT")) {
                return QueryResult.empty();
            }
            if (sql.contains("EXPLAIN FORMAT=JSON")) {
                return new QueryResult(List.of(row("EXPLAIN", """
                    {"query_block": {"cost_info": {"query_cost": "100.00"}, "table": {"schema_name": "shop", "table_name": "orders", "access_type": "ALL", "attached_condition": "(`orders`.`status` = 'ACTIVE')"}}}
                    """)));
            }
            if (sql.contains("information_schema.COLUMNS") && sql.contains("COLUMN_NAME")) {
                return new QueryResult(List.of(row("COLUMN_NAME", "status")));
            }
            if (sql.contains("information_schema.STATISTICS") && sql.contains("SEQ_IN_INDEX")) {
                return QueryResult.empty();
            }
            if (sql.contains("information_schema.TABLES") && sql.contains("TABLE_ROWS")) {
                return new QueryResult(List.of(row("row_estimate", 1000, "avg_row_length", 64)));
            }
            if (sql.contains("VERSION()")) {
                return new QueryResult(List.of(row("version", "8.0.32")));
            }
            if (sql.contains("DATABASE()")) {
                return new QueryResult(List.of(row("schema_name", "shop")));
            }
            if (sql.contains("innodb_page_size")) {
                return new QueryResult(List.of(row("value", 16384)));
            }
            if (sql.contains("SCHEMATA") && sql.contains("'sys'")) {
                return new QueryResult(List.of(row("cnt", 1)));
            }
            return QueryResult.empty();
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
