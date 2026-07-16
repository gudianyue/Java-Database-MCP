package dev.databasemcp.mcp;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.databasemcp.diagnostics.DiagnosticDialect;
import dev.databasemcp.diagnostics.DiagnosticDialectProvider;
import dev.databasemcp.diagnostics.ExplainPlanService;
import dev.databasemcp.dialect.DatabaseDialectProvider;
import dev.databasemcp.permission.MetricPermissionEnforcer;
import dev.databasemcp.permission.PermissionDeniedException;
import dev.databasemcp.permission.PermissionErrorCode;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "dta" })
    void analyzeWorkloadIndexesNormalizesSupportedMethodsToDta(String method) {
        DiagnosticDialect dialect = mock(DiagnosticDialect.class);
        when(diagnosticDialectProvider.current()).thenReturn(dialect);
        when(dialect.analyzeWorkloadIndexes(10000, "dta")).thenReturn("indexes");

        String result = facade.analyzeWorkloadIndexes(null, method);

        org.assertj.core.api.Assertions.assertThat(result).isEqualTo("indexes");
    }

    @ParameterizedTest
    @ValueSource(strings = { "unsupported", "DTA" })
    void analyzeWorkloadIndexesRejectsUnsupportedMethodBeforeAnalysis(String method) {
        String result = facade.analyzeWorkloadIndexes(10000, method);

        org.assertj.core.api.Assertions.assertThat(result).contains("仅支持 dta");
        verifyNoInteractions(diagnosticDialectProvider, sqlClient);
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void analyzeQueryIndexesDefaultsMissingMethodToDta(String method) {
        List<String> queries = List.of("select * from public.orders");
        DiagnosticDialect dialect = mock(DiagnosticDialect.class);
        when(diagnosticDialectProvider.current()).thenReturn(dialect);
        when(dialect.analyzeQueryIndexes(queries, 10000, "dta")).thenReturn("indexes");

        String result = facade.analyzeQueryIndexes(queries, null, method, "user-1");

        org.assertj.core.api.Assertions.assertThat(result).isEqualTo("indexes");
    }

    @ParameterizedTest
    @ValueSource(strings = { "unsupported", "DTA" })
    void analyzeQueryIndexesRejectsUnsupportedMethodBeforeAuthorization(String method) {
        String result = facade.analyzeQueryIndexes(
            List.of("select * from public.orders"),
            10000,
            method,
            "user-1"
        );

        org.assertj.core.api.Assertions.assertThat(result).contains("仅支持 dta");
        verifyNoInteractions(enforcer, diagnosticDialectProvider, sqlClient);
    }

    @Test
    void executeSqlRejectsUninspectableProtectedUnionBeforeQuerying() {
        String sql = """
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default'
            union all select * from gkschema.gk_qta_data
            """;
        doThrow(new PermissionDeniedException(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE))
            .when(enforcer).authorize(sql, "user-1");

        String result = facade.executeSql(sql, "user-1");

        org.assertj.core.api.Assertions.assertThat(result).contains("permission_sql_uninspectable");
        verifyNoInteractions(sqlClient);
    }
}
