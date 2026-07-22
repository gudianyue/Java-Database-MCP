package dev.databasemcp.permission;

import dev.databasemcp.config.DatabaseMcpProperties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** SQL 授权执行器，授权通过才放行，失败/超时/拒绝统一映射为 PermissionDeniedException。 */
@Component
public class SqlAuthorizationEnforcer {

    private static final Logger log = LoggerFactory.getLogger(SqlAuthorizationEnforcer.class);
    private static final String CONFIGURATION_ERROR = "exactly one SqlAuthorizer must be configured";

    private final DatabaseMcpProperties properties;
    private final SqlAuthorizer authorizer;

    @Autowired
    SqlAuthorizationEnforcer(DatabaseMcpProperties properties, ObjectProvider<SqlAuthorizer> authorizers) {
        this(properties, selectAuthorizer(properties, authorizers));
    }

    SqlAuthorizationEnforcer(DatabaseMcpProperties properties, SqlAuthorizer authorizer) {
        this.properties = properties;
        if (properties.getPermission().isEnabled() && authorizer == null) {
            throw new IllegalStateException(CONFIGURATION_ERROR);
        }
        this.authorizer = authorizer;
    }

    public void authorize(String sql, String userId) {
        if (!properties.getPermission().isEnabled()) {
            return;
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            log.warn(
                "SQL 授权拒绝：code={}, userId={}, sql={}, elapsedMs=0",
                PermissionErrorCode.PERMISSION_CONTEXT_MISSING.value(),
                userId,
                sql
            );
            throw new PermissionDeniedException(PermissionErrorCode.PERMISSION_CONTEXT_MISSING);
        }
        long startedAt = System.nanoTime();
        boolean allowed;
        try {
            allowed = authorizer.isAllowed(userId, sql);
        } catch (SqlAuthorizationTimeoutException e) {
            log.error(
                "SQL 授权失败：code={}, userId={}, sql={}, elapsedMs={}",
                PermissionErrorCode.PERMISSION_AUTHORIZER_TIMEOUT.value(),
                userId,
                sql,
                elapsedMillis(startedAt)
            );
            throw new PermissionDeniedException(PermissionErrorCode.PERMISSION_AUTHORIZER_TIMEOUT);
        } catch (RuntimeException e) {
            log.error(
                "SQL 授权失败：code={}, userId={}, sql={}, elapsedMs={}",
                PermissionErrorCode.PERMISSION_AUTHORIZER_UNAVAILABLE.value(),
                userId,
                sql,
                elapsedMillis(startedAt)
            );
            throw new PermissionDeniedException(PermissionErrorCode.PERMISSION_AUTHORIZER_UNAVAILABLE);
        }
        if (!allowed) {
            log.warn(
                "SQL 授权拒绝：code={}, userId={}, sql={}, elapsedMs={}",
                PermissionErrorCode.PERMISSION_DENIED.value(),
                userId,
                sql,
                elapsedMillis(startedAt)
            );
            throw new PermissionDeniedException(PermissionErrorCode.PERMISSION_DENIED);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static SqlAuthorizer selectAuthorizer(
        DatabaseMcpProperties properties,
        ObjectProvider<SqlAuthorizer> authorizers
    ) {
        if (!properties.getPermission().isEnabled()) {
            return null;
        }
        List<SqlAuthorizer> candidates = authorizers.stream().toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(CONFIGURATION_ERROR);
        }
        return candidates.getFirst();
    }
}
