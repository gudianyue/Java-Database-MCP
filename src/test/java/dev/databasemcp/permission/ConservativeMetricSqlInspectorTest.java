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
    void acceptsPostgresOnlyModifierForPublicSql() {
        MetricSqlInspection inspection = inspector.inspect("select orders.id from only (public.orders) * orders");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
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
    void acceptsPostgresTableQueryExpressionForPublicSql() {
        MetricSqlInspection inspection = inspector.inspect("table public.orders");

        assertThat(inspection.protectedResource()).isFalse();
        assertThat(inspection.inspectable()).isTrue();
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
