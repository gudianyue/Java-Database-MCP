package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SqlAuthorizationEnforcerTest {

    @Test
    void disabledAuthorizationBypassesInputValidationAndAuthorizer() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        AtomicBoolean called = new AtomicBoolean();
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(properties, (userId, sql) -> {
            called.set(true);
            return false;
        });

        enforcer.authorize(null, null);

        assertThat(called).isFalse();
    }

    @Test
    void enabledAuthorizationRejectsBlankSqlBeforeCallingAuthorizer() {
        DatabaseMcpProperties properties = enabledProperties();
        AtomicBoolean called = new AtomicBoolean();
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(properties, (userId, sql) -> {
            called.set(true);
            return true;
        });

        assertThatThrownBy(() -> enforcer.authorize("   ", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("SQL 不能为空");
        assertThat(called).isFalse();
    }

    @Test
    void enabledAuthorizationRejectsBlankUserIdBeforeCallingAuthorizer() {
        AtomicBoolean called = new AtomicBoolean();
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(enabledProperties(), (userId, sql) -> {
            called.set(true);
            return true;
        });

        assertThatThrownBy(() -> enforcer.authorize("select 1", "   "))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessage("permission_context_missing");
        assertThat(called).isFalse();
    }

    @Test
    void enabledAuthorizationPassesOriginalUserIdAndSqlToAuthorizer(CapturedOutput output) {
        AtomicReference<String> seenUserId = new AtomicReference<>();
        AtomicReference<String> seenSql = new AtomicReference<>();
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(enabledProperties(), (userId, sql) -> {
            seenUserId.set(userId);
            seenSql.set(sql);
            return true;
        });
        String userId = " user-1 ";
        String sql = "  select 1  ";

        enforcer.authorize(sql, userId);

        assertThat(seenUserId).hasValue(userId);
        assertThat(seenSql).hasValue(sql);
        assertThat(output).doesNotContain(userId).doesNotContain(sql);
    }

    @Test
    void deniedAuthorizationReturnsStablePermissionError() {
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(
            enabledProperties(),
            (userId, sql) -> false
        );

        assertThatThrownBy(() -> enforcer.authorize("select 1", "user-1"))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessage("permission_denied");
    }

    @Test
    void authorizerTimeoutReturnsStablePermissionError() {
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(enabledProperties(), (userId, sql) -> {
            throw new SqlAuthorizationTimeoutException("timed out");
        });

        assertThatThrownBy(() -> enforcer.authorize("select 1", "user-1"))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessage("permission_authorizer_timeout");
    }

    @Test
    void authorizerFailureReturnsStablePermissionErrorWithoutLeakingCause(CapturedOutput output) {
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(enabledProperties(), (userId, sql) -> {
            throw new IllegalStateException("remote response body");
        });

        assertThatThrownBy(() -> enforcer.authorize("select 1", "user-1"))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessage("permission_authorizer_unavailable");
        assertThat(output)
            .contains("ERROR")
            .contains("permission_authorizer_unavailable")
            .contains("userId=user-1")
            .contains("sql=select 1")
            .doesNotContain("remote response body");
    }

    @Test
    void customAuthorizerPermissionExceptionIsMappedToUnavailable(CapturedOutput output) {
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(enabledProperties(), (userId, sql) -> {
            throw new PermissionDeniedException(PermissionErrorCode.PERMISSION_DENIED);
        });

        assertThatThrownBy(() -> enforcer.authorize("select 1", "user-1"))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessage("permission_authorizer_unavailable");
        assertThat(output)
            .contains("ERROR")
            .contains("permission_authorizer_unavailable")
            .doesNotContain("permission_denied");
    }

    @Test
    void deniedAuthorizationLogsOriginalRequestAndStableError(CapturedOutput output) {
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(
            enabledProperties(),
            (userId, sql) -> false
        );

        assertThatThrownBy(() -> enforcer.authorize(" select secret ", " user-1 "))
            .isInstanceOf(PermissionDeniedException.class);

        assertThat(output)
            .contains("WARN")
            .contains("permission_denied")
            .contains("userId= user-1 ")
            .contains("sql= select secret ")
            .contains("elapsedMs=");
    }

    @Test
    void metricBusinessDenialUsesGenericErrorAndKeepsInternalDiagnostic(CapturedOutput output) {
        MetricPermissionEnforcer metricAuthorizer = metricAuthorizer(userId -> PermissionScope.empty());
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(enabledProperties(), metricAuthorizer);
        String sql = "select * from gkschema.gk_qta_data";

        assertThatThrownBy(() -> enforcer.authorize(sql, "user-1"))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessage("permission_denied");
        assertThat(output)
            .contains("WARN")
            .contains("diagnostic=sql_uninspectable")
            .contains("permission_denied")
            .contains("userId=user-1")
            .contains("sql=" + sql);
    }

    @Test
    void metricProviderTimeoutUsesGenericAuthorizerTimeout(CapturedOutput output) {
        MetricPermissionEnforcer metricAuthorizer = metricAuthorizer(userId -> {
            throw new MetricPermissionProviderTimeoutException("provider detail");
        });
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(enabledProperties(), metricAuthorizer);
        String sql = protectedSql();

        assertThatThrownBy(() -> enforcer.authorize(sql, "user-1"))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessage("permission_authorizer_timeout");
        assertThat(output)
            .contains("diagnostic=provider_timeout")
            .contains("permission_authorizer_timeout")
            .doesNotContain("provider detail");
    }

    @Test
    void metricProviderFailureUsesGenericAuthorizerUnavailable(CapturedOutput output) {
        MetricPermissionEnforcer metricAuthorizer = metricAuthorizer(userId -> {
            throw new IllegalStateException("provider detail");
        });
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(enabledProperties(), metricAuthorizer);
        String sql = protectedSql();

        assertThatThrownBy(() -> enforcer.authorize(sql, "user-1"))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessage("permission_authorizer_unavailable");
        assertThat(output)
            .contains("diagnostic=provider_unavailable")
            .contains("permission_authorizer_unavailable")
            .doesNotContain("provider detail");
    }

    private static MetricPermissionEnforcer metricAuthorizer(MetricPermissionProvider provider) {
        ConservativeMetricSqlInspector inspector = new ConservativeMetricSqlInspector(
            DatabaseType.POSTGRESQL,
            Set.of("gkschema.gk_qta_data"),
            Set.of("quota_id"),
            Set.of("quota_scene")
        );
        return new MetricPermissionEnforcer(inspector, provider);
    }

    private static String protectedSql() {
        return "select * from gkschema.gk_qta_data "
            + "where quota_id = 'A' and quota_scene = 'default'";
    }

    private static DatabaseMcpProperties enabledProperties() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.getPermission().setEnabled(true);
        return properties;
    }
}
