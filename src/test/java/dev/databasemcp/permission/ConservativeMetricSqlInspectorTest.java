package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

class ConservativeMetricSqlInspectorTest {

    private final ConservativeMetricSqlInspector inspector = new ConservativeMetricSqlInspector(
        DatabaseType.POSTGRESQL,
        Set.of("gkschema.gk_qta_data"),
        Set.of("quota_id"),
        Set.of("quota_scene")
    );

    @ParameterizedTest
    @CsvSource({
        "postgresql, POSTGRESQL",
        "mysql, MYSQL",
        "doris, DORIS",
        "dameng, DAMENG"
    })
    void assemblesConfiguredDatabaseTypeAtInspectorBoundary(
        String configuredType,
        DatabaseType expectedType
    ) {
        new ApplicationContextRunner()
            .withUserConfiguration(InspectorTestConfiguration.class)
            .withPropertyValues("database-mcp.database-type=" + configuredType)
            .run(context -> assertThat(
                ReflectionTestUtils.getField(
                    context.getBean(ConservativeMetricSqlInspector.class),
                    "databaseType"
                )
            ).isEqualTo(expectedType));
    }

    @Test
    void rejectsUnsupportedDatabaseTypeDuringConfigurationBinding() {
        new ApplicationContextRunner()
            .withUserConfiguration(InspectorTestConfiguration.class)
            .withPropertyValues("database-mcp.database-type=oracle")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
            });
    }

    @Test
    void foldsVisitorFailureIntoStableUninspectableResult() {
        SQLSelectStatement statement = mock(SQLSelectStatement.class);
        doThrow(new IllegalStateException("visitor-sensitive-detail")).when(statement).accept(any());

        MetricSqlInspection inspection;
        try (MockedStatic<SQLUtils> druid = mockStatic(SQLUtils.class)) {
            druid.when(() -> SQLUtils.parseStatements("select 1", DbType.postgresql))
                .thenReturn(List.of(statement));
            inspection = inspector.inspect("select 1");
        }

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DatabaseMcpProperties.class)
    @Import(ConservativeMetricSqlInspector.class)
    static class InspectorTestConfiguration {
    }

    @Test
    void bypassesSqlParsingWhenMetricPermissionIsDisabled() {
        ConservativeMetricSqlInspector disabledInspector = new ConservativeMetricSqlInspector(
            DatabaseType.POSTGRESQL,
            Set.of(),
            Set.of("quota_id"),
            Set.of("quota_scene")
        );

        MetricSqlInspection inspection = disabledInspector.inspect("select 'unclosed");

        assertThat(inspection.protectedResource()).isFalse();
    }

    @Test
    void rejectsUnsupportedPublicStatementBeforePublicFastPath() {
        MetricSqlInspection inspection = inspector.inspect("update public.orders set status = 'done'");

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsMultiplePublicStatementsBeforePublicFastPath() {
        MetricSqlInspection inspection = inspector.inspect("select 1 from public.orders; select 2");

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void acceptsPublicSelectWithoutFromAfterDialectParsing(DatabaseType databaseType) {
        MetricSqlInspection inspection = dialectInspector(databaseType).inspect("select 1");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void enforces64KiBLimitBeforePublicFastPath(DatabaseType databaseType) {
        String prefix = "select 1";
        String atLimit = prefix + " ".repeat(64 * 1024 - prefix.length());
        ConservativeMetricSqlInspector dialectInspector = dialectInspector(databaseType);

        MetricSqlInspection accepted = dialectInspector.inspect(atLimit);
        MetricSqlInspection rejected = dialectInspector.inspect(atLimit + " ");

        assertThat(accepted.protectedResource()).isFalse();
        assertThat(rejected.protectedResource()).isTrue();
        assertThat(rejected.inspectable()).isFalse();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("supportedDialectQueries")
    void inspectsSupportedMetricQueriesAcrossDialects(
        DatabaseType databaseType,
        String caseName,
        String sql,
        Set<MetricScope> expectedScopes
    ) {
        MetricSqlInspection inspection = dialectInspector(databaseType).inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes()).containsExactlyInAnyOrderElementsOf(expectedScopes);
    }

    private static Stream<Arguments> supportedDialectQueries() {
        List<InspectableCase> cases = List.of(
            new InspectableCase("full-name equality", """
                select * from gkschema.gk_qta_data
                where quota_id = 'A' and quota_scene = 'default'
                """, Set.of(new MetricScope("A", "default"))),
            new InspectableCase("terminal-name match", """
                select * from analytics.gk_qta_data
                where quota_id = 'A' and quota_scene = 'default'
                """, Set.of(new MetricScope("A", "default"))),
            new InspectableCase("alias", """
                select metric.* from gkschema.gk_qta_data metric
                where metric.quota_id = 'A' and metric.quota_scene = 'default'
                """, Set.of(new MetricScope("A", "default"))),
            new InspectableCase("join", """
                select metric.*
                from public.dimensions dimension
                join gkschema.gk_qta_data metric on metric.dimension_id = dimension.id
                where metric.quota_id = 'A' and metric.quota_scene = 'default'
                """, Set.of(new MetricScope("A", "default"))),
            new InspectableCase("comma join", """
                select metric.*
                from public.dimensions dimension, gkschema.gk_qta_data metric
                where metric.dimension_id = dimension.id
                  and metric.quota_id = 'A' and metric.quota_scene = 'default'
                """, Set.of(new MetricScope("A", "default"))),
            new InspectableCase("metric in", """
                select * from gkschema.gk_qta_data
                where quota_id in ('A', 'B') and quota_scene = 'default'
                """, Set.of(
                    new MetricScope("A", "default"),
                    new MetricScope("B", "default")
                )),
            new InspectableCase("scene in", """
                select * from gkschema.gk_qta_data
                where quota_id = 'A' and quota_scene in ('default', 'custom')
                """, Set.of(
                    new MetricScope("A", "default"),
                    new MetricScope("A", "custom")
                )),
            new InspectableCase("cartesian product", """
                select * from gkschema.gk_qta_data
                where quota_id in ('A', 'B') and quota_scene in ('default', 'custom')
                """, Set.of(
                    new MetricScope("A", "default"),
                    new MetricScope("A", "custom"),
                    new MetricScope("B", "default"),
                    new MetricScope("B", "custom")
                )),
            new InspectableCase("tuple in", """
                select * from gkschema.gk_qta_data
                where (quota_id, quota_scene) in (('A', 'default'), ('B', 'custom'))
                """, Set.of(
                    new MetricScope("A", "default"),
                    new MetricScope("B", "custom")
                )),
            new InspectableCase("additional filter", """
                select * from gkschema.gk_qta_data
                where quota_id = 'A' and quota_scene = 'default'
                  and (status = 'ready' or status = 'running')
                """, Set.of(new MetricScope("A", "default"))),
            new InspectableCase("optimizer hint", """
                select /*+ index(metric idx_qta) */ metric.*
                from gkschema.gk_qta_data metric
                where metric.quota_id = 'A' and metric.quota_scene = 'default'
                """, Set.of(new MetricScope("A", "default"))),
            new InspectableCase("ordinary comment", """
                select metric.* from gkschema.gk_qta_data metric
                /* ordinary comment */
                where metric.quota_id = 'A' and metric.quota_scene = 'default'
                """, Set.of(new MetricScope("A", "default")))
        );
        return Stream.of(DatabaseType.values())
            .flatMap(databaseType -> cases.stream()
                .map(testCase -> arguments(databaseType, testCase.name(), testCase.sql(), testCase.scopes())));
    }

    private static ConservativeMetricSqlInspector dialectInspector(DatabaseType databaseType) {
        return new ConservativeMetricSqlInspector(
            databaseType,
            Set.of("gkschema.gk_qta_data"),
            Set.of("quota_id"),
            Set.of("quota_scene")
        );
    }

    private record InspectableCase(String name, String sql, Set<MetricScope> scopes) {
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unsupportedDialectQueries")
    void rejectsUnsupportedMetricQueriesAcrossDialects(
        DatabaseType databaseType,
        String caseName,
        String sql
    ) {
        MetricSqlInspection inspection = dialectInspector(databaseType).inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    private static Stream<Arguments> unsupportedDialectQueries() {
        List<UninspectableCase> sharedCases = List.of(
            new UninspectableCase("empty", ""),
            new UninspectableCase("multiple statements", "select 1; select 2"),
            new UninspectableCase("non select", "update public.orders set status = 'done'"),
            new UninspectableCase("malformed", "select 'unclosed"),
            new UninspectableCase("unconsumed input", protectedQuery("quota_id = 'A' and quota_scene = 'default' }")),
            new UninspectableCase("cte", """
                with metric as (select * from gkschema.gk_qta_data)
                select * from metric where quota_id = 'A' and quota_scene = 'default'
                """),
            new UninspectableCase("recursive cte", """
                with recursive metric as (select * from gkschema.gk_qta_data)
                select * from metric where quota_id = 'A' and quota_scene = 'default'
                """),
            new UninspectableCase("from subquery", """
                select * from (select * from gkschema.gk_qta_data) metric
                where quota_id = 'A' and quota_scene = 'default'
                """),
            new UninspectableCase("predicate subquery", protectedQuery("""
                quota_id = 'A' and quota_scene = 'default'
                and exists (select 1 from public.orders)
                """)),
            new UninspectableCase("union", protectedQuery("quota_id = 'A' and quota_scene = 'default'")
                + " union select * from public.orders"),
            new UninspectableCase("intersect", protectedQuery("quota_id = 'A' and quota_scene = 'default'")
                + " intersect select * from public.orders"),
            new UninspectableCase("except", protectedQuery("quota_id = 'A' and quota_scene = 'default'")
                + " except select * from public.orders"),
            new UninspectableCase("minus", protectedQuery("quota_id = 'A' and quota_scene = 'default'")
                + " minus select * from public.orders"),
            new UninspectableCase("multiple protected relations", """
                select *
                from gkschema.gk_qta_data left_metric
                join gkschema.gk_qta_data right_metric on right_metric.id = left_metric.id
                where left_metric.quota_id = 'A' and left_metric.quota_scene = 'default'
                """),
            new UninspectableCase("ambiguous scope relation", """
                select *
                from gkschema.gk_qta_data metric
                join public.orders orders on orders.id = metric.order_id
                where quota_id = 'A' and quota_scene = 'default'
                """),
            new UninspectableCase("missing scene", protectedQuery("quota_id = 'A'")),
            new UninspectableCase("missing metric", protectedQuery("quota_scene = 'default'")),
            new UninspectableCase("empty metric", protectedQuery(
                "quota_id = '' and quota_scene = 'default'"
            )),
            new UninspectableCase("blank scene", protectedQuery(
                "quota_id = 'A' and quota_scene = '  '"
            )),
            new UninspectableCase("placeholder", protectedQuery("quota_id = ? and quota_scene = 'default'")),
            new UninspectableCase("null", protectedQuery("quota_id = null and quota_scene = 'default'")),
            new UninspectableCase("number", protectedQuery("quota_id = 1 and quota_scene = 'default'")),
            new UninspectableCase("column reference", protectedQuery(
                "quota_id = fallback_quota_id and quota_scene = 'default'"
            )),
            new UninspectableCase("function", protectedQuery(
                "quota_id = upper('A') and quota_scene = 'default'"
            )),
            new UninspectableCase("arithmetic", protectedQuery(
                "quota_id = ('A' + '') and quota_scene = 'default'"
            )),
            new UninspectableCase("repeated dimension", protectedQuery(
                "quota_id = 'A' and quota_id = 'B' and quota_scene = 'default'"
            )),
            new UninspectableCase("tuple scalar mix", protectedQuery("""
                (quota_id, quota_scene) in (('A', 'default'))
                and quota_id = 'A'
                """)),
            new UninspectableCase("non equality", protectedQuery(
                "quota_id <> 'A' and quota_scene = 'default'"
            )),
            new UninspectableCase("scope or", protectedQuery(
                "quota_id = 'A' or quota_scene = 'default'"
            )),
            new UninspectableCase("scope not", protectedQuery(
                "not (quota_id = 'A') and quota_scene = 'default'"
            )),
            new UninspectableCase("top level escape", protectedQuery("""
                quota_id = 'A' and quota_scene = 'default'
                or status = 'ready'
                """)),
            new UninspectableCase("executable comment", protectedQuery("""
                quota_id = 'A' and quota_scene = 'default'
                /*! or 1 = 1 */
                """)),
            new UninspectableCase("unknown table source", "select * from (values (1)) value_table(id)")
        );
        return Stream.of(DatabaseType.values())
            .flatMap(databaseType -> Stream.concat(
                sharedCases.stream().map(testCase -> arguments(databaseType, testCase.name(), testCase.sql())),
                Stream.of(arguments(databaseType, "dialect mismatch", dialectMismatchSql(databaseType)))
            ));
    }

    private static String protectedQuery(String predicate) {
        return "select * from gkschema.gk_qta_data where " + predicate;
    }

    private static String dialectMismatchSql(DatabaseType databaseType) {
        String predicate = " where quota_id = 'A' and quota_scene = 'default'";
        return switch (databaseType) {
            case POSTGRESQL -> "select * from gkschema.gk_qta_data" + predicate + " limit 0, 10";
            case MYSQL, DORIS, DAMENG ->
                "select distinct on (quota_id) * from gkschema.gk_qta_data" + predicate;
        };
    }

    private record UninspectableCase(String name, String sql) {
    }

    @Test
    void rejectsInspectableResultWithoutMetricScopes() {
        assertThatThrownBy(() -> MetricSqlInspection.inspectable(Set.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one metric scope");
    }

    @Test
    void ignoresProtectedTableNamesInCommentsAndStrings() {
        MetricSqlInspection inspection = inspector.inspect("""
            select '-- gkschema.gk_qta_data' as sample
            -- from gkschema.gk_qta_data
            from public.orders
            """);

        assertThat(inspection.protectedResource()).isFalse();
    }

    @Test
    void rejectsAmbiguousDashSequenceBeforeProtectedTableDetection() {
        String sql = "SELECT 1--1 FROM gkschema.gk_qta_data WHERE quota_id='A' AND quota_scene='default'";
        assertThat(sql).contains("1--1");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsAmbiguousDashSequenceInPublicSql() {
        String sql = "SELECT 1--1 FROM public.orders";
        assertThat(sql).contains("1--1");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void acceptsWhitespaceControlAndEofLineCommentsInPublicSql() {
        MetricSqlInspection whitespace = inspector.inspect("SELECT 1 -- comment\nFROM public.orders");
        MetricSqlInspection control = inspector.inspect("SELECT 1 --\nFROM public.orders");
        MetricSqlInspection eof = inspector.inspect("SELECT 1 FROM public.orders --");

        assertThat(whitespace.protectedResource()).isFalse();
        assertThat(whitespace.inspectable()).isTrue();
        assertThat(control.protectedResource()).isFalse();
        assertThat(control.inspectable()).isTrue();
        assertThat(eof.protectedResource()).isFalse();
        assertThat(eof.inspectable()).isTrue();
    }

    @Test
    void rejectsTaggedDollarQuoteBeforeProtectedTableDetection() {
        String sql = "SELECT $q$--x$q$ FROM gkschema.gk_qta_data "
            + "WHERE quota_id='A' AND quota_scene='default'";
        assertThat(sql).contains("$q$--x$q$");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsTaggedDollarQuoteInPublicSql() {
        String sql = "SELECT $q$--x$q$ FROM public.orders";
        assertThat(sql).contains("$q$--x$q$");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsUntaggedDollarQuoteBeforeProtectedTableDetection() {
        String sql = "SELECT $$--x$$ FROM gkschema.gk_qta_data "
            + "WHERE quota_id='A' AND quota_scene='default'";
        assertThat(sql).contains("$$--x$$");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsUnicodeTaggedDollarQuoteBeforeProtectedTableDetection() {
        String sql = "SELECT $权限$--x$权限$ FROM gkschema.gk_qta_data "
            + "WHERE quota_id='A' AND quota_scene='default'";
        assertThat(sql).contains("$权限$--x$权限$");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void acceptsDollarCharactersThatAreNotDollarQuoteOpeners() {
        MetricSqlInspection inspection = inspector.inspect("SELECT $1, foo$bar FROM public.orders");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void rejectsDamengQLiteralBeforeProtectedTableDetection() {
        String sql = "SELECT q'[foo'bar]' AS harmless FROM gkschema.gk_qta_data "
            + "WHERE quota_id='A' AND quota_scene='default' AND q'[x'y]' IS NOT NULL";
        assertThat(sql).contains("q'[foo'bar]'", "q'[x'y]'", "gkschema.gk_qta_data");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsDamengQLiteralInPublicSql() {
        String sql = "SELECT q'[foo'bar]' AS harmless FROM public.orders";
        assertThat(sql).contains("q'[foo'bar]'");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsUppercaseDamengQLiteralInPublicSql() {
        String sql = "SELECT Q'[alpha'beta]' FROM public.orders";
        assertThat(sql).contains("Q'[alpha'beta]'");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void acceptsQSeparatedFromQuoteByWhitespace() {
        MetricSqlInspection inspection = inspector.inspect("SELECT q '[x]' FROM public.orders");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void acceptsUppercaseQOpenerShapeInsideIdentifierContinuation() {
        MetricSqlInspection inspection = inspector.inspect("SELECT fooQ'[x]' FROM public.orders");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void rejectsBackslashEscapesBeforeProtectedTableDetection() {
        String sql = """
            SELECT 'foo\\'bar' AS harmless
            UNION SELECT quota_id FROM gkschema.gk_qta_data
            WHERE quota_id=0x41 AND quota_scene=0x64656661756c74
              AND 'x\\'y' IS NOT NULL
            """;
        assertThat(sql).contains("foo\\'bar", "x\\'y");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsAnyBackslashInSingleQuotedStringForPublicSql() {
        String sql = "select 'C:\\temp' from public.orders";
        assertThat(sql).contains("C:\\temp");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsBackslashEscapedDoubleQuoteBeforeProtectedTableDetection() {
        String sql = "SELECT \"a\\\"b\" AS harmless FROM gkschema.gk_qta_data "
            + "WHERE quota_id='A' AND quota_scene=\"c\\\"d\"";
        assertThat(sql).contains("a\\\"b", "c\\\"d");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsAnyBackslashInDoubleQuotedConstructForPublicSql() {
        String sql = "SELECT \"C:\\temp\" FROM public.orders";
        assertThat(sql).contains("C:\\temp");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsUnicodeEscapedQuotedProtectedRelation() {
        String sql = "SELECT quota_id FROM gkschema.U&\"gk!005Fqta!005Fdata\" UESCAPE '!' "
            + "WHERE quota_id='A' AND quota_scene='default'";
        assertThat(sql).contains("U&\"gk!005Fqta!005Fdata\"");
        assertThat(sql).contains("UESCAPE '!'");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsUnicodeEscapedQuotedIdentifierInPublicSql() {
        String sql = "SELECT U&\"d!0061ta\" UESCAPE '!' FROM public.orders";
        assertThat(sql).contains("U&\"d!0061ta\"");
        assertThat(sql).contains("UESCAPE '!'");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void acceptsSpacedBitwiseAndBeforeDoubleQuotedIdentifier() {
        MetricSqlInspection inspection = inspector.inspect("SELECT u & \"column\" FROM public.orders");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void acceptsUnicodePrefixShapeInsideIdentifier() {
        MetricSqlInspection inspection = inspector.inspect("SELECT fooU&\"bar\" FROM public.orders");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void acceptsUnicodeEscapedSingleQuotedStringShape() {
        MetricSqlInspection inspection = inspector.inspect("SELECT U&'data' FROM public.orders");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void acceptsStandardDoubledSingleQuoteInPublicSql() {
        MetricSqlInspection inspection = inspector.inspect("select 'it''s safe' from public.orders");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void acceptsBacktickQuotedIdentifiersAndIgnoresExecutableMarkersOutsideExecutableComments() {
        MetricSqlInspection inspection = inspector.inspect("""
            select '/*! OR 1 = 1 */' as sample
            from `gkschema`.`gk_qta_data`
            where `quota_id` = 'A'
              and `quota_scene` = 'default'
              /* OR 1 = 1 */
              -- /*! OR 1 = 1 */
            """);

        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes())
            .containsExactly(new MetricScope("A", "default"));
    }

    @Test
    void rejectsAmbiguousDoubleQuotedScopePredicates() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where "quota_id" = 'quota_id'
              and "quota_scene" = 'quota_scene'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsDoubleQuotedProtectedRelationConservatively() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from "gkschema"."gk_qta_data"
            where quota_id = 'A' and quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void extractsMetricScopeFromEqualityPredicates() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes()).containsExactly(new MetricScope("A", "default"));
    }

    @Test
    void extractsMetricScopesFromTupleInPredicate() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where (quota_id, quota_scene) in (('A', 'default'), ('B', 'custom'))
            """);

        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes()).containsExactlyInAnyOrder(
            new MetricScope("A", "default"),
            new MetricScope("B", "custom")
        );
    }

    @Test
    void rejectsTupleInWithEmptyMetricWithoutThrowing() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where (quota_id, quota_scene) in (('', 'default'))
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsTupleInWithBlankSceneWithoutThrowing() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where (quota_id, quota_scene) in (('A', '   '))
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsAllTupleScopesWhenOneSceneIsNullText() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where (quota_id, quota_scene) in (
                ('A', 'default'),
                ('B', 'null')
            )
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void extractsMetricInWithSingleScene() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id in ('A', 'B') and quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes()).containsExactlyInAnyOrder(
            new MetricScope("A", "default"), new MetricScope("B", "default")
        );
    }

    @Test
    void extractsSingleMetricWithSceneIn() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene in ('default', 'custom')
            """);

        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes()).containsExactlyInAnyOrder(
            new MetricScope("A", "default"), new MetricScope("A", "custom")
        );
    }

    @Test
    void expandsIndependentInPredicatesAsCartesianProduct() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id in ('A', 'B')
              and quota_scene in ('default', 'custom')
            """);

        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes()).containsExactlyInAnyOrder(
            new MetricScope("A", "default"), new MetricScope("A", "custom"),
            new MetricScope("B", "default"), new MetricScope("B", "custom")
        );
    }

    @Test
    void rejectsEmptyStringInWithoutThrowing() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id in ('') and quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsUnknownParameterTokenMixedIntoScopeIn() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id in ('A' ?) and quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsNumberTokenMixedIntoScopeIn() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id in ('A' 1) and quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsBlankAndNullTextEqualityScopeValues() {
        MetricSqlInspection blank = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = '   ' and quota_scene = 'default'
            """);
        MetricSqlInspection nullText = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'null' and quota_scene = 'default'
            """);

        assertThat(blank.inspectable()).isFalse();
        assertThat(nullText.inspectable()).isFalse();
    }

    @Test
    void rejectsMixingTupleInWithScalarScopePredicate() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where (quota_id, quota_scene) in (('A', 'default'))
              and quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsRepeatedPredicatesForSameScopeDimension() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id in ('A', 'B')
              and quota_id = 'A'
              and quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsScopePredicatesBoundToAnotherTableAlias() {
        MetricSqlInspection inspection = inspector.inspect("""
            select *
            from gkschema.gk_qta_data metric
            join public.other_data other on other.id = metric.id
            where other.quota_id = 'A' and other.quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsProtectedTerminalNameUsedAsAnotherTableAlias() {
        MetricSqlInspection inspection = inspector.inspect("""
            select *
            from gkschema.gk_qta_data metric
            join public.other_data gk_qta_data on gk_qta_data.id = metric.id
            where gk_qta_data.quota_id = 'A' and gk_qta_data.quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void acceptsScopePredicatesBoundToProtectedTableAlias() {
        MetricSqlInspection inspection = inspector.inspect("""
            select *
            from gkschema.gk_qta_data metric
            join public.other_data other on other.id = metric.id
            where metric.quota_id = 'A' and metric.quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes()).containsExactly(new MetricScope("A", "default"));
    }

    @Test
    void acceptsSchemaQualifiedProtectedScopeWithoutAliasInMultiRelationQuery() {
        MetricSqlInspection inspection = inspector.inspect("""
            select *
            from gkschema.gk_qta_data
            join public.other_data other on other.id = gkschema.gk_qta_data.id
            where gkschema.gk_qta_data.quota_id = 'A'
              and gkschema.gk_qta_data.quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes()).containsExactly(new MetricScope("A", "default"));
    }

    @Test
    void acceptsOrOnNonScopeColumnsWhenScopeIsFixed() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              and (status = 'ready' or status = 'running')
            """);

        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes())
            .containsExactly(new MetricScope("A", "default"));
    }

    @Test
    void acceptsNotOnNonScopeColumnsWhenScopeIsFixed() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              and status not in ('deleted', 'disabled')
            """);

        assertThat(inspection.inspectable()).isTrue();
        assertThat(inspection.metricScopes())
            .containsExactly(new MetricScope("A", "default"));
    }

    @Test
    void rejectsTopLevelOrThatCanEscapeFixedScope() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              and status = 'ready'
               or status = 'running'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsTopLevelDoublePipeThatCanEscapeFixedScope() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              and status = 'ready'
               || status = 'running'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsTopLevelXorThatCanEscapeFixedScope() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              and status = 'ready'
              xor status = 'running'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsTopLevelOrHiddenAfterBacktickQuotedBoundaryColumn() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              and `group` = 'x'
               or 1 = 1
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsTopLevelOrHiddenAfterDoubleQuotedBoundaryColumn() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              and "group" = 'x'
               or 1 = 1
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsMySqlExecutableComment() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              /*! OR 1 = 1 */
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsExecutableCommentBeforeProtectedTableDetection() {
        MetricSqlInspection inspection = inspector.inspect("""
            select 1
            /*! from gkschema.gk_qta_data */
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsUnclosedSingleQuotedString() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsUnclosedDoubleQuotedConstruct() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              and "status = 'ready'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsUnclosedBacktickQuotedIdentifier() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A'
              and quota_scene = 'default'
              and `status = 'ready'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsUnclosedBlockCommentBeforeProtectedTableDetection() {
        MetricSqlInspection inspection = inspector.inspect("""
            select 1
            /* from gkschema.gk_qta_data
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsOrAcrossCompleteScopeTuples() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where (quota_id = 'A' and quota_scene = 'default')
               or (quota_id = 'B' and quota_scene = 'custom')
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsCteThatReferencesProtectedTable() {
        MetricSqlInspection inspection = inspector.inspect("""
            with source as (select 1 as id)
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsSubqueryEvenWhenOuterScopeIsFixed() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default'
              and exists (select 1 from public.orders)
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsProtectedPostgresTableQueryExpressionInsideSubquery() {
        String sql = """
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default'
              and exists (table gkschema.gk_qta_data)
            """;
        assertThat(sql).contains("table gkschema.gk_qta_data", "gkschema.gk_qta_data");

        MetricSqlInspection inspection = inspector.inspect(sql);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsSecondProtectedRelationUsingPostgresOnlyModifier() {
        MetricSqlInspection inspection = inspector.inspect("""
            select hidden_metric.quota_id
            from gkschema.gk_qta_data metric
            join only gkschema.gk_qta_data hidden_metric on hidden_metric.id = metric.id
            where metric.quota_id = 'A' and metric.quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsParenthesizedProtectedRelationUsingPostgresOnlyModifierAndStar() {
        MetricSqlInspection inspection = inspector.inspect("""
            select hidden_metric.quota_id
            from gkschema.gk_qta_data metric
            join only (gkschema.gk_qta_data) * hidden_metric on hidden_metric.id = metric.id
            where metric.quota_id = 'A' and metric.quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
    }

    @Test
    void rejectsPostgresOnlyModifierBeforePublicFastPath() {
        MetricSqlInspection inspection = inspector.inspect("select orders.id from only (public.orders) * orders");

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void acceptsOnlyTextCommentAndIdentifierInInspectableProtectedSql() {
        MetricSqlInspection inspection = inspector.inspect("""
            select 'only' as only_label, only_flag
            from gkschema.gk_qta_data
            -- only is harmless text here
            where quota_id = 'A' and quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void rejectsPostgresTableQueryExpressionBeforePublicFastPath() {
        MetricSqlInspection inspection = inspector.inspect("table public.orders");

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void acceptsTableTextAndIdentifierInInspectableProtectedSql() {
        MetricSqlInspection inspection = inspector.inspect("""
            select 'table' as table_label, table_value
            from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void acceptsTableTextInNormalCommentForInspectableProtectedSql() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            -- table is harmless text here
            where quota_id = 'A' and quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void rejectsMultipleProtectedRelations() {
        MetricSqlInspection inspection = inspector.inspect("""
            select *
            from gkschema.gk_qta_data left_metric
            join gkschema.gk_qta_data right_metric on right_metric.id = left_metric.id
            where left_metric.quota_id = 'A'
              and left_metric.quota_scene = 'default'
            """);

        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsUnionWithUnfilteredProtectedBranch() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default'
            union all
            select * from gkschema.gk_qta_data
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsNegatedScopePredicate() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where not (quota_id = 'A' and quota_scene = 'default')
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsScopeComparisonsOutsideWhereClause() {
        MetricSqlInspection inspection = inspector.inspect("""
            select quota_id = 'A', quota_scene = 'default'
            from gkschema.gk_qta_data
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsBooleanReinterpretationOfScopePredicate() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A' is false and quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }

    @Test
    void rejectsNonEqualityOperatorsOnScopeColumns() {
        for (String operator : java.util.List.of(">=", "<=", "!=", "<>", "==")) {
            MetricSqlInspection inspection = inspector.inspect("""
                select * from gkschema.gk_qta_data
                where quota_id %s 'A' and quota_scene = 'default'
                """.formatted(operator));

            assertThat(inspection.protectedResource()).as(operator).isTrue();
            assertThat(inspection.inspectable()).as(operator).isFalse();
        }
    }

    @Test
    void detectsProtectedTableAfterCommaJoin() {
        MetricSqlInspection inspection = inspector.inspect("""
            select *
            from public.other_data other, gkschema.gk_qta_data metric
            where metric.quota_id = 'A' and metric.quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void matchesQualifiedAndUnqualifiedProtectedTableNamesConservatively() {
        ConservativeMetricSqlInspector unqualifiedInspector = new ConservativeMetricSqlInspector(
            DatabaseType.POSTGRESQL,
            Set.of("gk_qta_data"),
            Set.of("quota_id"),
            Set.of("quota_scene")
        );

        MetricSqlInspection inspection = unqualifiedInspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id = 'A' and quota_scene = 'default'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isTrue();
    }

    @Test
    void treatsUnsupportedProtectedDmlAsUninspectable() {
        MetricSqlInspection inspection = inspector.inspect("""
            update gkschema.gk_qta_data
            set quota_scene = 'default'
            where quota_id = 'A'
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
    }
}
