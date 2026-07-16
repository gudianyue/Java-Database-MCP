package dev.databasemcp.permission;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "database-mcp.permission.metric.provider.authorization-query")
public class ConfiguredSqlMetricPermissionProvider implements MetricPermissionProvider {

    private final SqlClient sqlClient;
    private final String authorizationQuery;
    private final List<String> sceneDelimiters;
    private final int timeoutSeconds;
    private final RedisMetricPermissionCache cache;

    @Autowired
    public ConfiguredSqlMetricPermissionProvider(
        SqlClient sqlClient,
        DatabaseMcpProperties properties,
        ObjectProvider<RedisMetricPermissionCache> cacheProvider
    ) {
        this(
            sqlClient,
            properties.getPermission().getMetric().getProvider().getAuthorizationQuery(),
            properties.getPermission().getMetric().getProvider().getSceneDelimiters(),
            properties.getPermission().getMetric().getProvider().getTimeoutSeconds(),
            cacheProvider.getIfAvailable()
        );
    }

    ConfiguredSqlMetricPermissionProvider(SqlClient sqlClient, String authorizationQuery, List<String> sceneDelimiters) {
        this(sqlClient, authorizationQuery, sceneDelimiters, 10, null);
    }

    ConfiguredSqlMetricPermissionProvider(
        SqlClient sqlClient,
        String authorizationQuery,
        List<String> sceneDelimiters,
        int timeoutSeconds
    ) {
        this(sqlClient, authorizationQuery, sceneDelimiters, timeoutSeconds, null);
    }

    ConfiguredSqlMetricPermissionProvider(
        SqlClient sqlClient,
        String authorizationQuery,
        List<String> sceneDelimiters,
        int timeoutSeconds,
        RedisMetricPermissionCache cache
    ) {
        if (authorizationQuery == null || authorizationQuery.isBlank()) {
            throw new IllegalArgumentException("authorizationQuery is required");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be greater than zero");
        }
        this.sqlClient = sqlClient;
        this.authorizationQuery = authorizationQuery;
        this.sceneDelimiters = sceneDelimiters == null || sceneDelimiters.isEmpty()
            ? List.of(",", "，", ";", "|")
            : List.copyOf(sceneDelimiters);
        this.timeoutSeconds = timeoutSeconds;
        this.cache = cache;
    }

    @Override
    public PermissionScope authorizedScopes(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (cache != null) {
            Optional<PermissionScope> cachedScope = cache.get(userId);
            if (cachedScope.isPresent()) {
                return cachedScope.get();
            }
        }
        PermissionScope scope = loadAuthorizedScopes(userId);
        if (cache != null) {
            cache.put(userId, scope);
        }
        return scope;
    }

    private PermissionScope loadAuthorizedScopes(String userId) {
        BoundAuthorizationQuery boundQuery = bindUserId(authorizationQuery, userId);
        QueryResult result;
        try {
            result = sqlClient.query(boundQuery.sql(), boundQuery.params(), timeoutSeconds);
        } catch (RuntimeException e) {
            if (hasCause(e, SQLTimeoutException.class)) {
                throw new MetricPermissionProviderTimeoutException("authorization query timed out", e);
            }
            throw e;
        }
        Set<MetricScope> scopes = new LinkedHashSet<>();
        for (Map<String, Object> row : result.rows()) {
            Object quotaId = findValue(row, "quotaid");
            Object quotaScenes = findValue(row, "quotascenes");
            if (quotaId == null || quotaScenes == null) {
                throw new IllegalStateException("authorization query must return quota_id and quota_scenes");
            }
            String quota = String.valueOf(quotaId).trim();
            if (quota.isBlank()) {
                throw new IllegalStateException("quota_id is blank");
            }
            List<String> scenes = splitScenes(quotaScenes);
            if (scenes.isEmpty()) {
                throw new IllegalStateException("quota_scenes is blank");
            }
            for (String scene : scenes) {
                scopes.add(new MetricScope(quota, scene));
            }
        }
        return new PermissionScope(scopes);
    }

    private BoundAuthorizationQuery bindUserId(String query, String userId) {
        String bound = query
            .replace("'[${p_user_id}]'", "?")
            .replace("'[${user_id}]'", "?")
            .replace("[${p_user_id}]", "?")
            .replace("[${user_id}]", "?")
            .replace("${p_user_id}", "?")
            .replace("${user_id}", "?");
        long placeholderCount = bound.chars().filter(character -> character == '?').count();
        if (placeholderCount == 1) {
            return new BoundAuthorizationQuery(bound, List.of(userId));
        }
        throw new IllegalArgumentException(
            "authorization query must contain exactly one ? or supported user_id placeholder"
        );
    }

    private static Object findValue(Map<String, Object> row, String expectedKey) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String normalized = entry.getKey().replace("_", "").toLowerCase(Locale.ROOT);
            if (normalized.equals(expectedKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private List<String> splitScenes(Object rawScenes) {
        if (rawScenes instanceof Iterable<?> iterable) {
            List<String> scenes = new ArrayList<>();
            for (Object item : iterable) {
                addScene(scenes, item);
            }
            return scenes;
        }
        String value = String.valueOf(rawScenes);
        String pattern = sceneDelimiters.stream()
            .filter(delimiter -> delimiter != null && !delimiter.isEmpty())
            .map(Pattern::quote)
            .reduce((left, right) -> left + "|" + right)
            .orElse(Pattern.quote(","));
        List<String> scenes = new ArrayList<>();
        for (String scene : value.split(pattern)) {
            addScene(scenes, scene);
        }
        return scenes;
    }

    private static void addScene(List<String> scenes, Object rawScene) {
        if (rawScene != null) {
            String scene = String.valueOf(rawScene).trim();
            if (!scene.isBlank()) {
                scenes.add(scene);
            }
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record BoundAuthorizationQuery(String sql, List<?> params) {
    }
}
