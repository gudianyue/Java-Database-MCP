package dev.databasemcp.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DorisDatabaseDialectTest {

    @Test
    void listSchemas_queriesSchemataAndNotPerformanceSchema() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(sqlClient);

        dialect.listSchemas();

        String sql = sqlClient.recordedSqls().get(0);
        assertThat(sql).contains("information_schema.SCHEMATA");
        assertThat(sql).doesNotContain("FROM performance_schema");
        assertThat(sql).doesNotContain("ALL_USERS");
    }

    @Test
    void listObjects_table_queriesBaseTableWithParam() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(sqlClient);

        dialect.listObjects("app", "table");

        String sql = sqlClient.recordedSqls().get(0);
        assertThat(sql).contains("information_schema.TABLES");
        assertThat(sql).contains("'BASE TABLE'");
        assertThat(sqlClient.recordedParams().get(0)).contains("app");
    }

    @Test
    void listObjects_view_queriesViewWithParam() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(sqlClient);

        dialect.listObjects("app", "view");

        String sql = sqlClient.recordedSqls().get(0);
        assertThat(sql).contains("'VIEW'");
        assertThat(sqlClient.recordedParams().get(0)).contains("app");
    }

    @Test
    void listObjects_sequence_throwsUnsupportedWithExpectedMessage() {
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.listObjects("app", "sequence"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Doris 不支持 PostgreSQL 风格的 sequence");
    }

    @Test
    void listObjects_extension_throwsUnsupportedWithExpectedMessage() {
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.listObjects("app", "extension"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Doris 不支持 PostgreSQL 风格的 extension");
    }

    @Test
    void listObjects_unknown_throwsIllegalArgument() {
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.listObjects("app", "weird"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的对象类型");
    }

    @Test
    void getObjectDetails_table_queriesColumnsAndStatistics() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(sqlClient);

        QueryResult result = dialect.getObjectDetails("app", "users", "table");

        String sql = sqlClient.recordedSqls().get(0);
        assertThat(sql).contains("information_schema.COLUMNS");
        // 锁定 5 个占位参数的顺序：schema / name（顶层） + schema / name / table_type（子查询占位）。
        assertThat(sqlClient.recordedParams().get(0))
            .containsExactly("app", "users", "app", "users", "BASE TABLE");
        // If the underlying query threw, the degraded fallback would embed the marker string
        // in the indexes row. RecordingSqlClient returns successfully, so the normal path applies.
        assertThat(result).isNotNull();
    }

    @Test
    void explain_select1_emitsPlainExplainNotFormatJson() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(sqlClient);

        dialect.explain("SELECT 1");

        String sql = sqlClient.recordedSqls().get(0);
        assertThat(sql).isEqualTo("EXPLAIN SELECT 1");
        assertThat(sql).doesNotContain("FORMAT=JSON");
    }

    @Test
    void explain_invalidSql_throwsViaReadOnlyQueryValidator() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(sqlClient);

        assertThatThrownBy(() -> dialect.explain("DROP TABLE users"))
            .isInstanceOf(RuntimeException.class);
        assertThat(sqlClient.recordedSqls()).isEmpty();
    }

    @Test
    void explain_runtimeException_messageIsMasked() {
        SqlClient throwingClient = new SqlClient() {
            @Override
            public QueryResult query(String sql) {
                throw new RuntimeException("password=hunter2");
            }

            @Override
            public QueryResult query(String sql, List<?> params) {
                throw new RuntimeException("password=hunter2");
            }
        };
        DorisDatabaseDialect dialect = new DorisDatabaseDialect(throwingClient);

        assertThatThrownBy(() -> dialect.explain("SELECT 1"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("****")
            .hasMessageNotContaining("hunter2");
    }

    private static final class RecordingSqlClient implements SqlClient {
        private final List<String> sqls = new ArrayList<>();
        private final List<List<Object>> params = new ArrayList<>();
        private final List<Map<String, Object>> rows;

        RecordingSqlClient(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        RecordingSqlClient() {
            this(List.of());
        }

        List<String> recordedSqls() {
            return sqls;
        }

        List<List<Object>> recordedParams() {
            return params;
        }

        @Override
        public QueryResult query(String sql) {
            sqls.add(sql);
            params.add(List.of());
            return new QueryResult(rows);
        }

        @Override
        public QueryResult query(String sql, List<?> bound) {
            sqls.add(sql);
            params.add(new ArrayList<>(bound));
            return new QueryResult(rows);
        }
    }
}