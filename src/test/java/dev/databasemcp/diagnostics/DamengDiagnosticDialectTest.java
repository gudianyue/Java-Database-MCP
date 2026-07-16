package dev.databasemcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DamengDiagnosticDialectTest {

    @Test
    void databaseTypeReturnsDameng() {
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(new RecordingSqlClient());

        assertThat(dialect.databaseType()).isEqualTo(DatabaseType.DAMENG);
    }

    @Test
    void 高消耗查询使用达梦SqlHistory视图() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(row("SQL_TEXT", "SELECT 1"));
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(sqlClient);

        String result = dialect.getTopQueries("mean_time", 5);

        assertThat(sqlClient.lastSql)
            .contains("V$SQL_HISTORY")
            .contains("TOP_SQL_TEXT")
            .contains("AVG(TIME_USED)");
        assertThat(sqlClient.lastParams).containsExactly(5);
        assertThat(result).contains("sort_by=mean_time").contains("SELECT 1");
    }

    @Test
    void 高消耗查询视图不可用时返回退化说明() {
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(new ThrowingSqlClient("no privilege on V$SQL_HISTORY"));

        String result = dialect.getTopQueries("total_time", 10);

        assertThat(result).contains("无法获取达梦高消耗查询").contains("no privilege");
    }

    @Test
    void 健康检查默认覆盖独立分区() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(sqlClient);

        String result = dialect.analyzeHealth(null);

        assertThat(result)
            .contains("索引健康")
            .contains("连接健康")
            .contains("等待事件健康")
            .contains("存储健康")
            .contains("序列健康")
            .contains("缓冲区和内存健康")
            .contains("约束健康");
        assertThat(sqlClient.sqlCalls)
            .anySatisfy(sql -> assertThat(sql).contains("ALL_INDEXES"))
            .anySatisfy(sql -> assertThat(sql).contains("V$SESSIONS"))
            .anySatisfy(sql -> assertThat(sql).contains("V$SYSTEM_EVENT"))
            .anySatisfy(sql -> assertThat(sql).contains("V$MEM_POOL"));
    }

    @Test
    void 健康检查拒绝无效类型() {
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.analyzeHealth("vacuum"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的达梦健康检查类型");
    }

    @Test
    void 健康检查单项失败时返回退化说明() {
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(new ThrowingSqlClient("view missing"));

        String result = dialect.analyzeHealth("connection");

        assertThat(result).contains("连接健康").contains("无法获取达梦连接健康").contains("view missing");
    }

    @Test
    void 工作负载索引建议使用SqlHistory视图() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(row("SQL_TEXT", "SELECT * FROM USERS"));
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(sqlClient);

        String result = dialect.analyzeWorkloadIndexes(1024, "dta");

        assertThat(sqlClient.lastSql).contains("V$SQL_HISTORY");
        assertThat(result).contains("method=rule_engine").contains("ALL_INDEXES/ALL_IND_COLUMNS");
    }

    @Test
    void 查询索引建议解释每条Select() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(row("PLAN", "NSET2"));
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(sqlClient);

        String result = dialect.analyzeQueryIndexes(List.of("SELECT * FROM USERS WHERE EMAIL = 'a'"), 1024, "dta");

        assertThat(sqlClient.sqlCalls).containsExactly("EXPLAIN SELECT * FROM USERS WHERE EMAIL = 'a'");
        assertThat(result).contains("method=rule_engine").contains("NSET2");
    }

    @Test
    void 查询索引建议拒绝不安全输入() {
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.analyzeQueryIndexes(List.of(), 1024, "dta"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> dialect.analyzeQueryIndexes(List.of("DELETE FROM USERS"), 1024, "dta"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("仅支持 SELECT 查询");
        assertThatThrownBy(() -> dialect.analyzeQueryIndexes(List.of("SELECT 1; DROP TABLE USERS"), 1024, "dta"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("拒绝多语句 SQL");
        assertThatThrownBy(() -> dialect.analyzeQueryIndexes(List.of("SELECT '--'; DROP TABLE USERS"), 1024, "dta"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("拒绝多语句 SQL");
        assertThatThrownBy(() -> dialect.analyzeQueryIndexes(List.of("WITH d AS (DELETE FROM USERS) SELECT * FROM USERS"), 1024, "dta"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("仅支持 SELECT 查询");
        assertThatThrownBy(() -> dialect.analyzeQueryIndexes(
            List.of("SELECT 1", "SELECT 2", "SELECT 3", "SELECT 4", "SELECT 5", "SELECT 6", "SELECT 7", "SELECT 8", "SELECT 9", "SELECT 10", "SELECT 11"),
            1024,
            "dta"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("最多支持 10 条");
    }

    @Test
    void llm方法返回暂未接入说明() {
        DamengDiagnosticDialect dialect = new DamengDiagnosticDialect(new RecordingSqlClient());

        assertThat(dialect.analyzeWorkloadIndexes(1024, "llm"))
            .contains("LLM 索引优化方法").contains("method='dta'");
    }

    private static QueryResult row(String key, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key, value);
        return new QueryResult(List.of(row));
    }

    private static final class RecordingSqlClient implements SqlClient {
        private final QueryResult result;
        private final List<String> sqlCalls = new ArrayList<>();
        private String lastSql;
        private List<Object> lastParams = List.of();

        private RecordingSqlClient() {
            this(QueryResult.empty());
        }

        private RecordingSqlClient(QueryResult result) {
            this.result = result;
        }

        @Override
        public QueryResult query(String sql) {
            this.lastSql = sql;
            this.lastParams = List.of();
            this.sqlCalls.add(sql);
            return result;
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            this.lastSql = sql;
            this.lastParams = List.copyOf(params);
            this.sqlCalls.add(sql);
            return result;
        }
    }

    private static final class ThrowingSqlClient implements SqlClient {
        private final String message;

        private ThrowingSqlClient(String message) {
            this.message = message;
        }

        @Override
        public QueryResult query(String sql) {
            throw new IllegalStateException(message);
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            throw new IllegalStateException(message);
        }
    }
}
