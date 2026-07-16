package dev.databasemcp.mcp;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.databasemcp.diagnostics.DiagnosticDialect;
import dev.databasemcp.diagnostics.DiagnosticDialectProvider;
import dev.databasemcp.diagnostics.ExplainPlanService;
import dev.databasemcp.dialect.DatabaseDialectProvider;
import dev.databasemcp.permission.MetricPermissionEnforcer;
import dev.databasemcp.permission.ConservativeMetricSqlInspector;
import dev.databasemcp.permission.MetricScope;
import dev.databasemcp.permission.PermissionScope;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DatabaseToolFacadePermissionTest {

    private final DatabaseDialectProvider databaseDialectProvider = mock(DatabaseDialectProvider.class);
    private final SqlClient sqlClient = mock(SqlClient.class);
    private final ExplainPlanService explainPlanService = mock(ExplainPlanService.class);
    private final DiagnosticDialectProvider diagnosticDialectProvider = mock(DiagnosticDialectProvider.class);
    private final MetricPermissionEnforcer enforcer = mock(MetricPermissionEnforcer.class);
    private final DatabaseToolFacade facade = new DatabaseToolFacade(
        databaseDialectProvider,
        sqlClient,
        explainPlanService,
        diagnosticDialectProvider,
        enforcer
    );

    @Test
    void executeSqlAuthorizesBeforeQuerying() {
        String sql = "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'";
        when(sqlClient.query(sql)).thenReturn(new QueryResult(List.of(Map.of("ok", true))));

        facade.executeSql(sql, "user-1");

        InOrder order = inOrder(enforcer, sqlClient);
        order.verify(enforcer).authorize(sql, "user-1");
        order.verify(sqlClient).query(sql);
    }

    @Test
    void explainQueryAuthorizesBeforeExplaining() {
        String sql = "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'";
        when(explainPlanService.explain(sql, false, List.of())).thenReturn("plan");

        facade.explainQuery(sql, false, List.of(), "user-1");

        InOrder order = inOrder(enforcer, explainPlanService);
        order.verify(enforcer).authorize(sql, "user-1");
        order.verify(explainPlanService).explain(sql, false, List.of());
    }

    @Test
    void analyzeQueryIndexesAuthorizesEveryQueryBeforeAnalyzing() {
        String protectedSql = "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'";
        String publicSql = "select * from public.orders";
        List<String> queries = List.of(protectedSql, publicSql);
        DiagnosticDialect dialect = mock(DiagnosticDialect.class);
        when(diagnosticDialectProvider.current()).thenReturn(dialect);
        when(dialect.analyzeQueryIndexes(queries, 10000, "dta")).thenReturn("indexes");

        facade.analyzeQueryIndexes(queries, 10000, "dta", "user-1");

        InOrder order = inOrder(enforcer, diagnosticDialectProvider, dialect);
        order.verify(enforcer).authorize(protectedSql, "user-1");
        order.verify(enforcer).authorize(publicSql, "user-1");
        order.verify(diagnosticDialectProvider).current();
        order.verify(dialect).analyzeQueryIndexes(queries, 10000, "dta");
    }

    @Test
    void executeSqlRejectsUninspectableProtectedUnionBeforeQuerying() {
        MetricPermissionEnforcer realEnforcer = new MetricPermissionEnforcer(
            new ConservativeMetricSqlInspector(
                Set.of("gkschema.gk_qta_data"),
                Set.of("quota_id"),
                Set.of("quota_scene")
            ),
            userId -> new PermissionScope(Set.of(new MetricScope("A", "default")))
        );
        DatabaseToolFacade realFacade = new DatabaseToolFacade(
            databaseDialectProvider,
            sqlClient,
            explainPlanService,
            diagnosticDialectProvider,
            realEnforcer
        );
        String sql = """
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default'
            union all select * from gkschema.gk_qta_data
            """;

        String result = realFacade.executeSql(sql, "user-1");

        org.assertj.core.api.Assertions.assertThat(result).contains("permission_sql_uninspectable");
        verifyNoInteractions(sqlClient);
    }
}
