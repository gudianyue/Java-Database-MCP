package dev.postgresmcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.postgresmcp.sql.QueryResult;
import dev.postgresmcp.sql.SqlClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopQueriesServiceTest {

    @Test
    void usesPg13TimingColumnsForMeanTime() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        TopQueriesService service = new TopQueriesService(sqlClient, new FakeExtensionService(sqlClient, true, 16));

        String result = service.getTopQueries("mean_time", 5);

        assertThat(sqlClient.lastSql).contains("mean_exec_time").contains("ORDER BY mean_exec_time DESC");
        assertThat(sqlClient.lastParams).containsExactlyElementsOf(List.of(5));
        assertThat(result).contains("单次平均执行时间").contains("SELECT * FROM users");
    }

    @Test
    void usesPg12TimingColumnsForTotalTime() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        TopQueriesService service = new TopQueriesService(sqlClient, new FakeExtensionService(sqlClient, true, 12));

        service.getTopQueries("total_time", 3);

        assertThat(sqlClient.lastSql).contains("total_time").contains("ORDER BY total_time DESC");
        assertThat(sqlClient.lastParams).containsExactlyElementsOf(List.of(3));
    }

    @Test
    void resourceQueryUsesWalBytesForPg13() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        TopQueriesService service = new TopQueriesService(sqlClient, new FakeExtensionService(sqlClient, true, 16));

        service.getTopQueries("resources", 10);

        assertThat(sqlClient.lastSql).contains("wal_bytes").contains("total_exec_time_frac");
    }

    @Test
    void returnsInstallMessageWhenPgStatStatementsIsMissing() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        TopQueriesService service = new TopQueriesService(sqlClient, new FakeExtensionService(sqlClient, false, 16));

        String result = service.getTopQueries("resources", 10);

        assertThat(result).contains("pg_stat_statements 扩展").contains("CREATE EXTENSION pg_stat_statements");
        assertThat(sqlClient.lastSql).isNull();
    }

    @Test
    void rejectsInvalidSortCriteria() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        TopQueriesService service = new TopQueriesService(sqlClient, new FakeExtensionService(sqlClient, true, 16));

        assertThatThrownBy(() -> service.getTopQueries("calls", 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无效排序条件");
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
