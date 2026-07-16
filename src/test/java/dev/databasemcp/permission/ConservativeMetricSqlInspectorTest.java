package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ConservativeMetricSqlInspectorTest {

    private final ConservativeMetricSqlInspector inspector = new ConservativeMetricSqlInspector(
        Set.of("gkschema.gk_qta_data"),
        Set.of("quota_id"),
        Set.of("quota_scene")
    );

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
    void rejectsIndependentInPredicatesBecauseTheyLoseTupleSemantics() {
        MetricSqlInspection inspection = inspector.inspect("""
            select * from gkschema.gk_qta_data
            where quota_id in ('A', 'B') and quota_scene in ('default', 'custom')
            """);

        assertThat(inspection.protectedResource()).isTrue();
        assertThat(inspection.inspectable()).isFalse();
        assertThat(inspection.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SQL_UNINSPECTABLE);
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
