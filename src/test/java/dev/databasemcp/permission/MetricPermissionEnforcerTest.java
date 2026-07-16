package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MetricPermissionEnforcerTest {

    private final ConservativeMetricSqlInspector inspector = new ConservativeMetricSqlInspector(
        Set.of("gkschema.gk_qta_data"),
        Set.of("quota_id"),
        Set.of("quota_scene")
    );

    @Test
    void doesNotCallProviderForNonProtectedSql() {
        AtomicBoolean called = new AtomicBoolean(false);
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            called.set(true);
            return PermissionScope.empty();
        });

        enforcer.authorize("select * from public.orders", PermissionContext.none(), PermissionUsage.EXECUTE);

        assertThatCode(() -> {
            if (called.get()) {
                throw new AssertionError("provider should not be called");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsProtectedSqlWhenMetricContextIsMissing() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> new PermissionScope(Set.of(new MetricScope("A", "default")))
        );

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'",
            PermissionContext.none(),
            PermissionUsage.EXECUTE
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_context_missing");
    }

    @Test
    void rejectsDeclaredScopeOutsideProviderAuthorization() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> new PermissionScope(Set.of(new MetricScope("A", "default")))
        );

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'B' and quota_scene = 'custom'",
            context(Map.of("quota_id", "B", "quota_scene", "custom")),
            PermissionUsage.EXECUTE
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_denied");
    }

    @Test
    void rejectsSqlEvidenceThatDoesNotMatchDeclaredScopes() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> new PermissionScope(Set.of(
                new MetricScope("A", "default"),
                new MetricScope("B", "custom")
            ))
        );

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'",
            context(Map.of("quota_id", "B", "quota_scene", "custom")),
            PermissionUsage.EXECUTE
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_sql_mismatch");
    }

    @Test
    void rejectsProviderFailureFailClosed() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            throw new IllegalStateException("provider down");
        });

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'",
            context(Map.of("quota_id", "A", "quota_scene", "default")),
            PermissionUsage.EXECUTE
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_provider_unavailable");
    }

    @Test
    void rejectsProviderTimeoutWithStableErrorCode() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            throw new MetricPermissionProviderTimeoutException("authorization query timed out");
        });

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'",
            context(Map.of("quota_id", "A", "quota_scene", "default")),
            PermissionUsage.EXECUTE
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_provider_timeout");
    }

    @Test
    void rejectsProtectedSqlWhenProviderIsMissing() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            (MetricPermissionProvider) null
        );

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'",
            context(Map.of("quota_id", "A", "quota_scene", "default")),
            PermissionUsage.EXECUTE
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_plugin_disabled_or_missing");
    }

    private static PermissionContext context(Map<String, Object> scope) {
        return PermissionContext.metric("user-1", List.of(scope));
    }
}
