package dev.postgresmcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.postgresmcp.sql.QueryResult;
import dev.postgresmcp.sql.SqlClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseHealthServiceTest {

    @Test
    void defaultsToAllHealthChecks() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseHealthService service = new DatabaseHealthService(sqlClient);

        String result = service.analyze(null);

        assertThat(result)
            .contains("索引健康")
            .contains("连接健康")
            .contains("Vacuum 健康")
            .contains("序列健康")
            .contains("复制健康")
            .contains("缓冲区健康")
            .contains("约束健康");
        assertThat(sqlClient.sqlCalls).anySatisfy(sql -> assertThat(sql).contains("pg_stat_activity"));
    }

    @Test
    void supportsCommaSeparatedHealthTypes() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DatabaseHealthService service = new DatabaseHealthService(sqlClient);

        String result = service.analyze("connection, buffer");

        assertThat(result).contains("连接健康").contains("缓冲区健康");
        assertThat(result).doesNotContain("索引健康");
    }

    @Test
    void rejectsInvalidHealthTypes() {
        DatabaseHealthService service = new DatabaseHealthService(new RecordingSqlClient());

        assertThatThrownBy(() -> service.analyze("storage"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无效健康检查类型");
    }

    @Test
    void reportsIndividualCheckFailureWithoutStoppingOtherChecks() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        sqlClient.failReplicationSlots = true;
        DatabaseHealthService service = new DatabaseHealthService(sqlClient);

        String result = service.analyze("replication,connection");

        assertThat(result).contains("复制健康").contains("检查失败");
        assertThat(result).contains("连接健康").contains("连接健康：12 个总连接");
    }

    @Test
    void formatsIndexWarningsFromRows() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        sqlClient.invalidIndexRows = List.of(row(
            "schema", "public",
            "index", "users_email_idx",
            "table", "users"
        ));
        DatabaseHealthService service = new DatabaseHealthService(sqlClient);

        String result = service.analyze("index");

        assertThat(result).contains("public.users_email_idx").contains("无效");
    }

    private static final class RecordingSqlClient implements SqlClient {
        private final List<String> sqlCalls = new ArrayList<>();
        private boolean failReplicationSlots;
        private List<Map<String, Object>> invalidIndexRows = List.of();

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
                return new QueryResult(invalidIndexRows);
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
                if (failReplicationSlots) {
                    throw new IllegalStateException("permission denied for pg_replication_slots");
                }
                return QueryResult.empty();
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
