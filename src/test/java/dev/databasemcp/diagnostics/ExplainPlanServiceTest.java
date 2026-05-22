package dev.databasemcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExplainPlanServiceTest {

    @Test
    void explainsQueryWithTextPlan() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(sqlClient, new FakeExtensionService(sqlClient, false, 16));

        String result = service.explain("SELECT * FROM users", false, List.of());

        assertThat(sqlClient.sqlCalls).containsExactly("EXPLAIN (FORMAT TEXT) SELECT * FROM users");
        assertThat(result).contains("Seq Scan on users").contains("cost=");
    }

    @Test
    void rejectsAnalyzeWithHypotheticalIndexes() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(sqlClient, new FakeExtensionService(sqlClient, true, 16));

        assertThatThrownBy(() -> service.explain("SELECT * FROM users", true, List.of(Map.of("table", "users", "columns", List.of("email")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不能同时使用");
    }

    @Test
    void createsHypotheticalIndexesWithQuotedIdentifiers() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(sqlClient, new FakeExtensionService(sqlClient, true, 16));

        service.explain("SELECT * FROM public.users WHERE email = 'a@example.com'", false, List.of(
            Map.of("table", "public.users", "columns", List.of("email"), "using", "btree")
        ));

        assertThat(sqlClient.sqlCalls).contains(
            "SELECT hypopg_reset()",
            "SELECT * FROM hypopg_create_index(?)",
            "EXPLAIN (FORMAT TEXT) SELECT * FROM public.users WHERE email = 'a@example.com'"
        );
        assertThat(sqlClient.paramsCalls)
            .anySatisfy(params -> assertThat(params).containsExactlyElementsOf(
                List.of("CREATE INDEX ON \"public\".\"users\" USING btree (\"email\")")
            ));
    }

    @Test
    void returnsHypopgInstallMessageWhenExtensionIsMissing() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(sqlClient, new FakeExtensionService(sqlClient, false, 16));

        String result = service.explain("SELECT * FROM users", false, List.of(Map.of("table", "users", "columns", List.of("email"))));

        assertThat(result).contains("hypopg 扩展").contains("CREATE EXTENSION hypopg");
        assertThat(sqlClient.sqlCalls).isEmpty();
    }

    @Test
    void rejectsMutatingExplainAnalyze() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(sqlClient, new FakeExtensionService(sqlClient, false, 16));

        assertThatThrownBy(() -> service.explain("DELETE FROM users", true, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EXPLAIN ANALYZE 不允许执行 DELETE");
    }

    private static final class RecordingSqlClient implements SqlClient {
        private final List<String> sqlCalls = new ArrayList<>();
        private final List<List<Object>> paramsCalls = new ArrayList<>();

        @Override
        public QueryResult query(String sql) {
            this.sqlCalls.add(sql);
            this.paramsCalls.add(List.of());
            return planResult();
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            this.sqlCalls.add(sql);
            this.paramsCalls.add(new ArrayList<>(params));
            return planResult();
        }

        private static QueryResult planResult() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("QUERY PLAN", "Seq Scan on users  (cost=0.00..1.01 rows=1 width=32)");
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
