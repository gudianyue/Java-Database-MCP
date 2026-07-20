package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseType;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@ExtendWith(OutputCaptureExtension.class)
class MetricPermissionEnforcerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(EnforcerConfiguration.class)
        .withPropertyValues("database-mcp.permission.metric.enabled=true");

    private final ConservativeMetricSqlInspector inspector = newInspector();

    @Test
    void failsStartupWithoutProvider() {
        contextRunner.run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseMessage("exactly one MetricPermissionProvider must be configured"));
    }

    @Test
    void startsWithExactlyOneProvider() {
        contextRunner
            .withUserConfiguration(PrimaryProviderConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(MetricPermissionEnforcer.class);
            });
    }

    @Test
    void failsStartupWithMultipleProvidersEvenWhenOneIsPrimary() {
        contextRunner
            .withUserConfiguration(PrimaryProviderConfiguration.class, SecondProviderConfiguration.class)
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("exactly one MetricPermissionProvider must be configured"));
    }

    @Test
    void ignoresMultipleProvidersWhenMetricAuthorizationIsDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(
                EnforcerConfiguration.class,
                PrimaryProviderConfiguration.class,
                SecondProviderConfiguration.class
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(MetricPermissionEnforcer.class);
            });
    }

    @Test
    void doesNotCallProviderForNonProtectedSqlEvenWithoutUserId() {
        AtomicBoolean called = new AtomicBoolean(false);
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            called.set(true);
            return PermissionScope.empty();
        });

        assertThat(enforcer.isAllowed(null, "select * from public.orders")).isTrue();

        assertThat(called).isFalse();
    }

    @Test
    void bypassesParserAndProviderWhenMetricPermissionIsDisabled() {
        ConservativeMetricSqlInspector disabledInspector = new ConservativeMetricSqlInspector(
            DatabaseType.POSTGRESQL,
            Set.of(),
            Set.of("quota_id"),
            Set.of("quota_scene")
        );
        AtomicBoolean called = new AtomicBoolean(false);
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(disabledInspector, userId -> {
            called.set(true);
            return PermissionScope.empty();
        });

        assertThat(enforcer.isAllowed(null, "select 'unclosed")).isTrue();

        assertThat(called).isFalse();
    }

    @Test
    void rejectsUninspectableSqlWithInternalDiagnosticOnly(CapturedOutput output) {
        AtomicBoolean called = new AtomicBoolean(false);
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            called.set(true);
            return PermissionScope.empty();
        });
        String sql = "select 'sensitive-sql-marker";

        assertThat(enforcer.isAllowed("user-1", sql)).isFalse();
        assertThat(called).isFalse();
        assertThat(output)
            .contains("diagnostic=sql_uninspectable")
            .contains("userId=user-1")
            .contains("sql=" + sql)
            .doesNotContain("druid")
            .doesNotContain("syntax");
    }

    @Test
    void rejectsProtectedSqlWhenUserIdIsMissing() {
        AtomicBoolean called = new AtomicBoolean(false);
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            called.set(true);
            return new PermissionScope(Set.of(new MetricScope("A", "default")));
        });

        assertThat(enforcer.isAllowed(
            " ",
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'"
        )).isFalse();
        assertThat(called).isFalse();
    }

    @Test
    void authorizesScopesExtractedFromSql() {
        AtomicReference<String> requestedUserId = new AtomicReference<>();
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> {
                requestedUserId.set(userId);
                return new PermissionScope(Set.of(new MetricScope("A", "default")));
            }
        );

        assertThat(enforcer.isAllowed(
            " user-1 ",
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'"
        )).isTrue();
        assertThat(requestedUserId).hasValue("user-1");
    }

    @Test
    void rejectsUnauthorizedScopeAsGenericBusinessDecision(CapturedOutput output) {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> new PermissionScope(Set.of(new MetricScope("A", "default")))
        );
        String sql = "select * from gkschema.gk_qta_data where quota_id = 'B' and quota_scene = 'custom'";

        assertThat(enforcer.isAllowed("user-1", sql)).isFalse();
        assertThat(output)
            .contains("diagnostic=scope_denied")
            .contains("userId=user-1")
            .contains("sql=" + sql);
    }

    @Test
    void rejectsPartiallyAuthorizedScope() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> new PermissionScope(Set.of(new MetricScope("A", "default")))
        );

        assertThat(enforcer.isAllowed(
            "user-1",
            "select * from gkschema.gk_qta_data where quota_id in ('A', 'B') and quota_scene = 'default'"
        )).isFalse();
    }

    @Test
    void rejectsEmptyProviderAuthorization() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> PermissionScope.empty()
        );

        assertThat(enforcer.isAllowed(
            "user-1",
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'"
        )).isFalse();
    }

    @Test
    void rejectsNullProviderResult() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> null
        );

        assertThat(enforcer.isAllowed(
            "user-1",
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'"
        )).isFalse();
    }

    @Test
    void mapsProviderFailureToOrdinaryAuthorizerFailure() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            throw new IllegalStateException("provider down");
        });

        assertThatThrownBy(() -> enforcer.isAllowed(
            "user-1",
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("permission_provider_unavailable")
            .hasMessageNotContaining("provider down");
    }

    @Test
    void mapsProviderTimeoutToPublicAuthorizerTimeout() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            throw new MetricPermissionProviderTimeoutException("authorization query timed out");
        });

        assertThatThrownBy(() -> enforcer.isAllowed(
            "user-1",
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'"
        ))
            .isInstanceOf(SqlAuthorizationTimeoutException.class)
            .hasMessageNotContaining("permission_provider_timeout");
    }

    @Test
    void requiresProviderWhenConstructedDirectly() {
        assertThatThrownBy(() -> new MetricPermissionEnforcer(inspector, (MetricPermissionProvider) null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("exactly one MetricPermissionProvider must be configured");
    }

    private static ConservativeMetricSqlInspector newInspector() {
        return new ConservativeMetricSqlInspector(
            DatabaseType.POSTGRESQL,
            Set.of("gkschema.gk_qta_data"),
            Set.of("quota_id"),
            Set.of("quota_scene")
        );
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MetricPermissionEnforcer.class)
    static class EnforcerConfiguration {

        @Bean
        ConservativeMetricSqlInspector metricSqlInspector() {
            return newInspector();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryProviderConfiguration {

        @Bean
        @Primary
        MetricPermissionProvider primaryMetricPermissionProvider() {
            return userId -> PermissionScope.empty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondProviderConfiguration {

        @Bean
        MetricPermissionProvider secondMetricPermissionProvider() {
            return userId -> PermissionScope.empty();
        }
    }
}
