package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.databasemcp.config.DatabaseMcpProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisMetricPermissionCacheTest {

    private static final String KEY_PREFIX = "test:metric-permission:";
    private static final String USER_KEY = KEY_PREFIX
        + "c6c289e49e9c05b2145860387b73bcb18df43fb09a1e4a4a9713c76c88bb541b";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private RedisMetricPermissionCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = new ObjectMapper();
        cache = new RedisMetricPermissionCache(redisTemplate, objectMapper, properties(300, KEY_PREFIX));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveTtlWhenMetricAuthorizationAndCacheAreEnabled(int ttlSeconds) {
        DatabaseMcpProperties properties = properties(ttlSeconds, KEY_PREFIX);
        properties.getPermission().getMetric().setEnabled(true);
        properties.getPermission().getMetric().getProvider().getCache().setEnabled(true);

        assertThatThrownBy(() -> new RedisMetricPermissionCache(redisTemplate, objectMapper, properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(
                "database-mcp.permission.metric.provider.cache.ttl-seconds must be greater than zero"
            );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankKeyPrefixWhenMetricAuthorizationAndCacheAreEnabled(String keyPrefix) {
        DatabaseMcpProperties properties = properties(300, keyPrefix);
        properties.getPermission().getMetric().setEnabled(true);
        properties.getPermission().getMetric().getProvider().getCache().setEnabled(true);

        assertThatThrownBy(() -> new RedisMetricPermissionCache(redisTemplate, objectMapper, properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(
                "database-mcp.permission.metric.provider.cache.key-prefix must not be blank"
            );
    }

    @Test
    void ignoresInvalidCacheConfigurationWhenMetricAuthorizationIsDisabled() {
        DatabaseMcpProperties properties = properties(-1, null);
        properties.getPermission().getMetric().getProvider().getCache().setEnabled(true);

        assertThatCode(() -> new RedisMetricPermissionCache(redisTemplate, objectMapper, properties))
            .doesNotThrowAnyException();
    }

    @Test
    void returnsPermissionScopeDeserializedByRealObjectMapper() throws Exception {
        PermissionScope expected = new PermissionScope(Set.of(
            new MetricScope("quota-a", "scene-1"),
            new MetricScope("quota-b", "scene-2")
        ));
        when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(expected));

        Optional<PermissionScope> result = cache.get("user-1");

        assertThat(result).contains(expected);
    }

    @Test
    void returnsEmptyOnCacheMiss() {
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThat(cache.get("user-1")).isEmpty();
    }

    @Test
    void hashesUserIdsIntoStableDistinctKeysWithoutExposingRawIds() {
        when(valueOperations.get(anyString())).thenReturn(null);

        cache.get("user-1");
        cache.get("user-2");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, org.mockito.Mockito.times(2)).get(keyCaptor.capture());
        assertThat(keyCaptor.getAllValues()).containsExactly(
            KEY_PREFIX + "c6c289e49e9c05b2145860387b73bcb18df43fb09a1e4a4a9713c76c88bb541b",
            KEY_PREFIX + "d92b69cfb82cecab45c753f56002b2c0e6d01f132fe39f2c878858dc9528e10b"
        );
        assertThat(keyCaptor.getAllValues()).noneMatch(key -> key.contains("user-1") || key.contains("user-2"));
    }

    @Test
    void deletesCorruptedJsonAndReturnsEmpty() {
        when(valueOperations.get(anyString())).thenReturn("{not-json");

        assertThat(cache.get("user-1")).isEmpty();

        verify(redisTemplate).delete(USER_KEY);
    }

    @Test
    void deletesObjectWithoutScopesAndReturnsEmpty() {
        when(valueOperations.get(anyString())).thenReturn("{}");

        assertThat(cache.get("user-1")).isEmpty();

        verify(redisTemplate).delete(USER_KEY);
    }

    @Test
    void deletesObjectWithNullScopesAndReturnsEmpty() {
        when(valueOperations.get(anyString())).thenReturn("{\"scopes\":null}");

        assertThat(cache.get("user-1")).isEmpty();

        verify(redisTemplate).delete(USER_KEY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPermissionScopeJson")
    void deletesStructurallyInvalidPermissionScopeJson(String description, String json) {
        when(valueOperations.get(anyString())).thenReturn(json);

        assertThat(cache.get("user-1")).isEmpty();

        verify(redisTemplate).delete(USER_KEY);
    }

    @Test
    void keepsObjectWithEmptyScopesArrayAsValidEmptyPermission() {
        when(valueOperations.get(anyString())).thenReturn("{\"scopes\":[]}");

        assertThat(cache.get("user-1")).contains(PermissionScope.empty());

        verify(redisTemplate, org.mockito.Mockito.never()).delete(USER_KEY);
    }

    @Test
    void returnsEmptyWhenRedisReadFails() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis unavailable"));

        assertThat(cache.get("user-1")).isEmpty();
    }

    @Test
    void writesRealPermissionScopeJsonWithConfiguredTtl() throws Exception {
        PermissionScope scope = new PermissionScope(Set.of(new MetricScope("quota-a", "scene-1")));

        cache.put("user-1", scope);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
            org.mockito.ArgumentMatchers.eq(USER_KEY),
            jsonCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(300))
        );
        JsonNode json = objectMapper.readTree(jsonCaptor.getValue());
        assertThat(json.path("scopes").get(0).path("quotaId").asText()).isEqualTo("quota-a");
        assertThat(json.path("scopes").get(0).path("quotaScene").asText()).isEqualTo("scene-1");
    }

    @Test
    void writesEmptyPermissionScope() throws Exception {
        cache.put("user-1", PermissionScope.empty());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
            org.mockito.ArgumentMatchers.anyString(),
            jsonCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(300))
        );
        assertThat(objectMapper.readTree(jsonCaptor.getValue()).path("scopes")).isEmpty();
    }

    @Test
    void doesNotPropagateRedisWriteFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis unavailable"))
            .when(valueOperations)
            .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));

        assertThatCode(() -> cache.put("user-1", PermissionScope.empty())).doesNotThrowAnyException();
    }

    @Test
    void springContextDoesNotCreateCacheByDefault() {
        new ApplicationContextRunner()
            .withUserConfiguration(CacheContextConfiguration.class)
            .run(context -> assertThat(context).doesNotHaveBean(RedisMetricPermissionCache.class));
    }

    @Test
    void springContextCreatesCacheWhenExplicitlyEnabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(CacheContextConfiguration.class)
            .withPropertyValues("database-mcp.permission.metric.provider.cache.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(RedisMetricPermissionCache.class));
    }

    private static DatabaseMcpProperties properties(int ttlSeconds, String keyPrefix) {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        DatabaseMcpProperties.CacheProperties cache = properties.getPermission()
            .getMetric()
            .getProvider()
            .getCache();
        cache.setTtlSeconds(ttlSeconds);
        cache.setKeyPrefix(keyPrefix);
        return properties;
    }

    private static Stream<Arguments> invalidPermissionScopeJson() {
        return Stream.of(
            Arguments.of("array root", "[]"),
            Arguments.of("numeric root", "42"),
            Arguments.of("object scopes", "{\"scopes\":{}}"),
            Arguments.of("text scopes", "{\"scopes\":\"not-array\"}"),
            Arguments.of("missing quotaId", "{\"scopes\":[{\"quotaScene\":\"S\"}]}"),
            Arguments.of("missing quotaScene", "{\"scopes\":[{\"quotaId\":\"Q\"}]}"),
            Arguments.of("numeric quotaId", "{\"scopes\":[{\"quotaId\":1,\"quotaScene\":\"scene-1\"}]}"),
            Arguments.of("boolean quotaScene", "{\"scopes\":[{\"quotaId\":\"quota-a\",\"quotaScene\":true}]}"),
            Arguments.of("array quotaId", "{\"scopes\":[{\"quotaId\":[],\"quotaScene\":\"scene-1\"}]}"),
            Arguments.of("object quotaScene", "{\"scopes\":[{\"quotaId\":\"quota-a\",\"quotaScene\":{}}]}"),
            Arguments.of("null quotaId", "{\"scopes\":[{\"quotaId\":null,\"quotaScene\":\"scene-1\"}]}"),
            Arguments.of("blank quotaId", "{\"scopes\":[{\"quotaId\":\" \",\"quotaScene\":\"scene-1\"}]}"),
            Arguments.of("non-object scope", "{\"scopes\":[\"quota-a\"]}"),
            Arguments.of("unknown scope field", "{\"scopes\":[{\"quotaId\":\"quota-a\",\"quotaScene\":\"scene-1\",\"extra\":true}]}"),
            Arguments.of("unknown root field", "{\"scopes\":[],\"extra\":true}")
        );
    }

    @Configuration
    @EnableConfigurationProperties(DatabaseMcpProperties.class)
    @Import(RedisMetricPermissionCache.class)
    static class CacheContextConfiguration {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
