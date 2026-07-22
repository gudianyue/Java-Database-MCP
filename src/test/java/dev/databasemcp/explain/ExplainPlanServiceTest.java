package dev.databasemcp.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.dialect.DamengDatabaseDialect;
import dev.databasemcp.dialect.DatabaseDialect;
import dev.databasemcp.dialect.DatabaseDialectProvider;
import dev.databasemcp.dialect.MySqlDatabaseDialect;
import dev.databasemcp.dialect.PostgresDatabaseDialect;
import dev.databasemcp.diagnostics.PostgresExtensionService;
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
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new PostgresDatabaseDialect(sqlClient))
        );

        String result = service.explain("SELECT * FROM users", false, List.of());

        assertThat(sqlClient.sqlCalls).containsExactly("EXPLAIN (FORMAT TEXT) SELECT * FROM users");
        assertThat(result).contains("Seq Scan on users").contains("cost=");
    }

    @Test
    void 拒绝同时使用Analyze和假设索引() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, true, 16),
            new FixedDialectProvider(new PostgresDatabaseDialect(sqlClient))
        );

        assertThatThrownBy(() -> service.explain("SELECT * FROM users", true, List.of(Map.of("table", "users", "columns", List.of("email")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("analyze 和 hypothetical_indexes 不能同时使用");
    }

    @Test
    void createsHypotheticalIndexesWithQuotedIdentifiers() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, true, 16),
            new FixedDialectProvider(new PostgresDatabaseDialect(sqlClient))
        );

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
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new PostgresDatabaseDialect(sqlClient))
        );

        String result = service.explain("SELECT * FROM users", false, List.of(Map.of("table", "users", "columns", List.of("email"))));

        assertThat(result).contains("hypopg 扩展").contains("CREATE EXTENSION hypopg");
        assertThat(sqlClient.sqlCalls).isEmpty();
    }

    @Test
    void rejectsMutatingExplainAnalyze() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new PostgresDatabaseDialect(sqlClient))
        );

        assertThatThrownBy(() -> service.explain("DELETE FROM users", true, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EXPLAIN ANALYZE 不能执行 DELETE");
    }

    @Test
    void mysqlExplainUsesCurrentDialectWithFormatJson() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(mysqlPlanResult());
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new MySqlDatabaseDialect(sqlClient))
        );

        String result = service.explain("SELECT * FROM users", false, List.of());

        assertThat(sqlClient.sqlCalls).containsExactly("EXPLAIN FORMAT=JSON SELECT * FROM users");
        assertThat(result).contains("table=users").contains("type=ALL");
        assertThat(result).isNotEqualTo("1");
    }

    @Test
    void mysqlRejectsHypotheticalIndexes() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new MySqlDatabaseDialect(sqlClient))
        );

        String result = service.explain(
            "SELECT * FROM users",
            false,
            List.of(Map.of("table", "users", "columns", List.of("email")))
        );

        assertThat(result).contains("MySQL 分支不支持 hypothetical_indexes");
    }

    @Test
    void mysqlRejectsAnalyzeWithoutRunningSql() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new MySqlDatabaseDialect(sqlClient))
        );

        String result = service.explain("SELECT * FROM users", true, List.of());

        assertThat(result).contains("MySQL 分支不支持 analyze=true");
        assertThat(sqlClient.sqlCalls).isEmpty();
    }

    @Test
    void damengExplainUsesCurrentDialect() {
        RecordingSqlClient sqlClient = new RecordingSqlClient(damengPlanResult());
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new DamengDatabaseDialect(sqlClient))
        );

        String result = service.explain("SELECT * FROM USERS", false, List.of());

        assertThat(sqlClient.sqlCalls).containsExactly("EXPLAIN SELECT * FROM USERS");
        assertThat(result).contains("PLAN");
    }

    @Test
    void damengRejectsHypotheticalIndexes() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new DamengDatabaseDialect(sqlClient))
        );

        String result = service.explain(
            "SELECT * FROM USERS",
            false,
            List.of(Map.of("table", "USERS", "columns", List.of("EMAIL")))
        );

        assertThat(result).contains("达梦分支不支持 hypothetical_indexes");
        assertThat(sqlClient.sqlCalls).isEmpty();
    }

    @Test
    void damengRejectsAnalyzeWithoutRunningSql() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new DamengDatabaseDialect(sqlClient))
        );

        String result = service.explain("SELECT * FROM USERS", true, List.of());

        assertThat(result).contains("达梦分支不支持 analyze=true");
        assertThat(sqlClient.sqlCalls).isEmpty();
    }

    @Test
    void damengExplainRejectsMultiStatementSqlWithoutRunningSql() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new DamengDatabaseDialect(sqlClient))
        );

        assertThatThrownBy(() -> service.explain("SELECT * FROM USERS; DROP TABLE USERS", false, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("拒绝多语句 SQL");
        assertThat(sqlClient.sqlCalls).isEmpty();
    }

    @Test
    void damengExplainRejectsMutatingSqlWithoutRunningSql() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new DamengDatabaseDialect(sqlClient))
        );

        assertThatThrownBy(() -> service.explain("DELETE FROM USERS", false, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("仅支持 SELECT 查询");
        assertThat(sqlClient.sqlCalls).isEmpty();
    }

    @Test
    void damengExplainRejectsWithCteWithoutRunningSql() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        ExplainPlanService service = new ExplainPlanService(
            sqlClient,
            new FakeExtensionService(sqlClient, false, 16),
            new FixedDialectProvider(new DamengDatabaseDialect(sqlClient))
        );

        assertThatThrownBy(() -> service.explain("WITH d AS (DELETE FROM USERS) SELECT * FROM USERS", false, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("仅支持 SELECT 查询");
        assertThat(sqlClient.sqlCalls).isEmpty();
    }

    private static final class RecordingSqlClient implements SqlClient {
        private final List<String> sqlCalls = new ArrayList<>();
        private final List<List<Object>> paramsCalls = new ArrayList<>();
        private final QueryResult result;

        private RecordingSqlClient() {
            this(postgresPlanResult());
        }

        private RecordingSqlClient(QueryResult result) {
            this.result = result;
        }

        @Override
        public QueryResult query(String sql) {
            this.sqlCalls.add(sql);
            this.paramsCalls.add(List.of());
            return result;
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            this.sqlCalls.add(sql);
            this.paramsCalls.add(new ArrayList<>(params));
            return result;
        }
    }

    private static QueryResult postgresPlanResult() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("QUERY PLAN", "Seq Scan on users  (cost=0.00..1.01 rows=1 width=32)");
        return new QueryResult(List.of(row));
    }

    private static QueryResult mysqlPlanResult() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("table", "users");
        row.put("type", "ALL");
        return new QueryResult(List.of(row));
    }

    private static QueryResult damengPlanResult() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PLAN", "1 #NSET2: [0, 1, 1]");
        return new QueryResult(List.of(row));
    }

    private static final class FixedDialectProvider extends DatabaseDialectProvider {
        private final DatabaseDialect dialect;

        private FixedDialectProvider(DatabaseDialect dialect) {
            super(null, List.of());
            this.dialect = dialect;
        }

        @Override
        public DatabaseDialect current() {
            return dialect;
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
