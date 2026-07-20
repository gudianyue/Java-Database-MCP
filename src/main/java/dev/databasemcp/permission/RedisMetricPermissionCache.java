package dev.databasemcp.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.databasemcp.config.DatabaseMcpProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "database-mcp.permission.metric.provider.cache.enabled",
    havingValue = "true"
)
class RedisMetricPermissionCache {

    private static final Logger log = LoggerFactory.getLogger(RedisMetricPermissionCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final String keyPrefix;

    RedisMetricPermissionCache(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        DatabaseMcpProperties properties
    ) {
        DatabaseMcpProperties.CacheProperties cache = properties.getPermission()
            .getMetric()
            .getProvider()
            .getCache();
        boolean shouldValidateCacheConfiguration = properties.getPermission().getMetric().isEnabled()
            && cache.isEnabled();
        if (shouldValidateCacheConfiguration && cache.getTtlSeconds() <= 0) {
            throw new IllegalStateException(
                "database-mcp.permission.metric.provider.cache.ttl-seconds must be greater than zero"
            );
        }
        if (shouldValidateCacheConfiguration && (cache.getKeyPrefix() == null || cache.getKeyPrefix().isBlank())) {
            throw new IllegalStateException(
                "database-mcp.permission.metric.provider.cache.key-prefix must not be blank"
            );
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(cache.getTtlSeconds());
        this.keyPrefix = cache.getKeyPrefix();
    }

    Optional<PermissionScope> get(String userId) {
        String key = key(userId);
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException exception) {
            warn("read", exception);
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            if (!hasValidStructure(root)) {
                log.warn(
                    "Metric permission cache operation={} failed exceptionType={}",
                    "invalid-value",
                    "InvalidStructure"
                );
                deleteCorruptedValue(key);
                return Optional.empty();
            }
            PermissionScope scope = objectMapper.treeToValue(root, PermissionScope.class);
            if (scope != null) {
                return Optional.of(scope);
            }
        } catch (JsonProcessingException exception) {
            warn("deserialize", exception);
        }
        deleteCorruptedValue(key);
        return Optional.empty();
    }

    private static boolean hasValidStructure(JsonNode root) {
        if (root == null || !root.isObject() || root.size() != 1) {
            return false;
        }
        JsonNode scopes = root.get("scopes");
        if (scopes == null || !scopes.isArray()) {
            return false;
        }
        for (JsonNode scope : scopes) {
            if (!scope.isObject() || scope.size() != 2) {
                return false;
            }
            JsonNode quotaId = scope.get("quotaId");
            JsonNode quotaScene = scope.get("quotaScene");
            if (!isNonBlankText(quotaId) || !isNonBlankText(quotaScene)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNonBlankText(JsonNode value) {
        return value != null && !value.isNull() && value.isTextual() && !value.textValue().isBlank();
    }

    void put(String userId, PermissionScope scope) {
        String key = key(userId);
        try {
            String json = objectMapper.writeValueAsString(scope);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (JsonProcessingException | RuntimeException exception) {
            warn("write", exception);
        }
    }

    private void deleteCorruptedValue(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            warn("delete", exception);
        }
    }

    private String key(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(userId.getBytes(StandardCharsets.UTF_8));
            return keyPrefix + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void warn(String operation, Exception exception) {
        log.warn(
            "Metric permission cache operation={} failed exceptionType={}",
            operation,
            exception.getClass().getSimpleName()
        );
    }
}
