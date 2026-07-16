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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DorisDiagnosticDialectTest {

    @Test
    void getTopQueries_aggregatesFromAuditLog() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(row(
            "query", "SELECT 1",
            "query_count", 3,
            "total_query_time_ms", 100,
            "avg_query_time_ms", 33
        ));
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(sqlClient);

        String result = dialect.getTopQueries("mean_time", 5);

        assertThat(result).contains("mean_time").contains("limit=5");
        assertThat(sqlClient.recordedSqls.get(sqlClient.recordedSqls.size() - 1))
            .contains("__internal_schema.audit_log");
    }

    @Test
    void analyzeHealth_dorisAuditLog_runsAndRendersRows() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(row(
            "user_name", "alice",
            "query", "SELECT 1",
            "query_count", 12,
            "last_active", "2026-07-06"
        ));
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(sqlClient);

        String result = dialect.analyzeHealth("doris_audit_log");

        assertThat(result).contains("## 审计日志健康").contains("alice");
        assertThat(sqlClient.recordedSqls.get(sqlClient.recordedSqls.size() - 1))
            .contains("__internal_schema.audit_log");
    }

    @Test
    void analyzeHealth_dorisCompaction_runsAndRendersRows() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(row(
            "backend_id", 10001,
            "host", "192.168.1.1",
            "alive", true,
            "tablet_num", 42,
            "last_heartbeat", "2026-07-06T10:00:00"
        ));
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(sqlClient);

        String result = dialect.analyzeHealth("doris_compaction");

        assertThat(result).contains("## Compaction 健康").contains("10001");
        assertThat(sqlClient.recordedSqls.get(sqlClient.recordedSqls.size() - 1))
            .contains("information_schema.BACKENDS");
    }

    @Test
    void analyzeHealth_dorisTabletHealth_runsAndRendersRows() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(row(
            "tablet_id", 12345,
            "backend_id", 10001,
            "version_count", 2,
            "row_count", 999,
            "last_check_time", "2026-07-06T10:00:00"
        ));
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(sqlClient);

        String result = dialect.analyzeHealth("doris_tablet_health");

        assertThat(result).contains("## Tablet 健康").contains("12345");
        assertThat(sqlClient.recordedSqls.get(sqlClient.recordedSqls.size() - 1))
            .contains("tablet_id");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "vacuum", "fragmentation", "sequence", "auto_increment",
        "wait", "storage", "replication", "index",
        "connection", "buffer", "constraint"
    })
    void analyzeHealth_legacyHealthTypes_eachThrowsUnsupportedOperationException(String legacyType) {
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.analyzeHealth(legacyType))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageStartingWith("Doris 不支持");
    }

    @Test
    void analyzeHealth_all_callsAllThreePrimitives() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(sqlClient);

        String result = dialect.analyzeHealth("all");

        assertThat(result)
            .contains("审计日志健康")
            .contains("Compaction 健康")
            .contains("Tablet 健康");
    }

    @Test
    void analyzeHealth_unknown_throwsIllegalArgumentException() {
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.analyzeHealth("made_up_type"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Doris 仅支持")
            .hasMessageContaining("made_up_type");
    }

    @Test
    void analyzeHealth_throwingClient_eachSectionDegradesGracefully() {
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(new ThrowingSqlClient("audit_log not accessible"));

        String result = dialect.analyzeHealth("all");

        assertThat(result).contains("当前权限或版本无法获取 Doris");
    }

    @Test
    void analyzeWorkloadIndexes_dta_runsAgainstAuditLog() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(row(
            "query", "SELECT 1",
            "executions", 5,
            "total_query_time_ms", 200
        ));
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(sqlClient);

        String result = dialect.analyzeWorkloadIndexes(1024, "dta");

        assertThat(result).contains("method=dta");
        assertThat(sqlClient.recordedSqls.get(sqlClient.recordedSqls.size() - 1))
            .contains("audit_log");
    }

    @Test
    void analyzeQueryIndexes_selectIssuesPerQueryExplain() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(row("plan", "NSET2"));
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(sqlClient);

        String result = dialect.analyzeQueryIndexes(List.of("SELECT 1"), 1024, "dta");

        assertThat(result).isNotNull();
        assertThat(sqlClient.recordedSqls.get(sqlClient.recordedSqls.size() - 1))
            .contains("EXPLAIN SELECT 1")
            .doesNotContain("FORMAT=JSON");
    }

    @Test
    void analyzeHealth_errorMessageIsMasked() {
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(new ThrowingSqlClient("password=hunter2 leakage"));

        String result = dialect.analyzeHealth("doris_audit_log");

        assertThat(result).contains("****").doesNotContain("hunter2");
    }

    @Test
    void databaseTypeReturnsDoris() {
        DorisDiagnosticDialect dialect = new DorisDiagnosticDialect(new RecordingSqlClient());

        assertThat(dialect.databaseType()).isEqualTo(DatabaseType.DORIS);
    }

    private static QueryResult row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return new QueryResult(List.of(row));
    }

    static final class RecordingSqlClient implements SqlClient {
        private final QueryResult result;
        private final List<String> recordedSqls = new ArrayList<>();
        private final List<List<Object>> recordedParams = new ArrayList<>();

        RecordingSqlClient() {
            this(QueryResult.empty());
        }

        RecordingSqlClient(QueryResult result) {
            this.result = result;
        }

        @Override
        public QueryResult query(String sql) {
            recordedSqls.add(sql);
            recordedParams.add(List.of());
            return result;
        }

        @Override
        public QueryResult query(String sql, List<?> bound) {
            recordedSqls.add(sql);
            recordedParams.add(List.copyOf(bound));
            return result;
        }
    }

    static final class ThrowingSqlClient implements SqlClient {
        private final String message;

        ThrowingSqlClient(String message) {
            this.message = message;
        }

        @Override
        public QueryResult query(String sql) {
            throw new IllegalStateException(message);
        }

        @Override
        public QueryResult query(String sql, List<?> bound) {
            throw new IllegalStateException(message);
        }
    }
}
