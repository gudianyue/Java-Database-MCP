package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import dev.databasemcp.config.DatabaseType;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MetricPermissionEnforcerTest {

    private final ConservativeMetricSqlInspector inspector = new ConservativeMetricSqlInspector(
        DatabaseType.POSTGRESQL,
        Set.of("gkschema.gk_qta_data"),
        Set.of("quota_id"),
        Set.of("quota_scene")
    );

    @Test
    void doesNotCallProviderForNonProtectedSqlEvenWithoutUserId() {
        AtomicBoolean called = new AtomicBoolean(false);
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            called.set(true);
            return PermissionScope.empty();
        });

        enforcer.authorize("select * from public.orders", null);

        assertThatCode(() -> {
            if (called.get()) {
                throw new AssertionError("provider should not be called");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsProtectedSqlWhenUserIdIsMissing() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> new PermissionScope(Set.of(new MetricScope("A", "default")))
        );

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'",
            " "
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_context_missing");
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

        assertThatCode(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'",
            " user-1 "
        )).doesNotThrowAnyException();
        assertThat(requestedUserId).hasValue("user-1");
    }

    @Test
    void rejectsSqlScopeOutsideProviderAuthorization() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> new PermissionScope(Set.of(new MetricScope("A", "default")))
        );

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'B' and quota_scene = 'custom'",
            "user-1"
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_denied");
    }

    @Test
    void rejectsNullProviderResult() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(
            inspector,
            userId -> null
        );

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'",
            "user-1"
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_denied");
    }

    @Test
    void rejectsProviderFailureFailClosed() {
        MetricPermissionEnforcer enforcer = new MetricPermissionEnforcer(inspector, userId -> {
            throw new IllegalStateException("provider down");
        });

        assertThatThrownBy(() -> enforcer.authorize(
            "select * from gkschema.gk_qta_data where quota_id = 'A' and quota_scene = 'default'",
            "user-1"
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
            "user-1"
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
            "user-1"
        ))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessageContaining("permission_plugin_disabled_or_missing");
    }
}
