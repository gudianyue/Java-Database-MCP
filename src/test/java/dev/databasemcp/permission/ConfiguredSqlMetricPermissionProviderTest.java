package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ConfiguredSqlMetricPermissionProviderTest {

    @Test
    void splitsMultipleScenesIntoMetricScopeTuples() {
        SqlClient sqlClient = mock(SqlClient.class);
        when(sqlClient.query("select quota_id, quota_scenes from auth where user_id = ?", List.of("user-1"), 10))
            .thenReturn(new QueryResult(List.of(Map.of(
                "quota_id", "A",
                "quota_scenes", "default，custom;public|private"
            ))));
        ConfiguredSqlMetricPermissionProvider provider = new ConfiguredSqlMetricPermissionProvider(
            sqlClient,
            "select quota_id, quota_scenes from auth where user_id = ?",
            List.of(",", "，", ";", "|")
        );

        PermissionScope scope = provider.authorizedScopes("user-1");

        assertThat(scope.scopes()).containsExactlyInAnyOrder(
            new MetricScope("A", "default"),
            new MetricScope("A", "custom"),
            new MetricScope("A", "public"),
            new MetricScope("A", "private")
        );
    }

    @Test
    void convertsQuotedPUserIdPlaceholderToParameterizedQuery() {
        SqlClient sqlClient = mock(SqlClient.class);
        when(sqlClient.query("select quota_id, quota_scenes from auth where user_id = ?", List.of("user-1"), 10))
            .thenReturn(new QueryResult(List.of(Map.of("quota_id", "A", "quota_scenes", "default"))));
        ConfiguredSqlMetricPermissionProvider provider = new ConfiguredSqlMetricPermissionProvider(
            sqlClient,
            "select quota_id, quota_scenes from auth where user_id = '[${p_user_id}]'",
            List.of(",")
        );

        assertThat(provider.authorizedScopes("user-1").scopes())
            .containsExactly(new MetricScope("A", "default"));
    }

    @Test
    void convertsUserIdPlaceholderToParameterizedQuery() {
        SqlClient sqlClient = mock(SqlClient.class);
        when(sqlClient.query("select quota_id, quota_scenes from auth where user_id = ?", List.of("user-1"), 10))
            .thenReturn(new QueryResult(List.of(Map.of("quota_id", "A", "quota_scenes", "default"))));
        ConfiguredSqlMetricPermissionProvider provider = new ConfiguredSqlMetricPermissionProvider(
            sqlClient,
            "select quota_id, quota_scenes from auth where user_id = ${user_id}",
            List.of(",")
        );

        assertThat(provider.authorizedScopes("user-1").scopes())
            .containsExactly(new MetricScope("A", "default"));
    }

    @Test
    void throwsWhenRequiredColumnsAreMissing() {
        SqlClient sqlClient = mock(SqlClient.class);
        when(sqlClient.query("select quota_id from auth where user_id = ?", List.of("user-1"), 10))
            .thenReturn(new QueryResult(List.of(Map.of("quota_id", "A"))));
        ConfiguredSqlMetricPermissionProvider provider = new ConfiguredSqlMetricPermissionProvider(
            sqlClient,
            "select quota_id from auth where user_id = ?",
            List.of(",")
        );

        assertThatThrownBy(() -> provider.authorizedScopes("user-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("quota_id and quota_scenes");
    }

    @Test
    void rejectsAuthorizationQueryWithMultipleQuestionMarks() {
        SqlClient sqlClient = mock(SqlClient.class);
        ConfiguredSqlMetricPermissionProvider provider = new ConfiguredSqlMetricPermissionProvider(
            sqlClient,
            "select quota_id, quota_scenes from auth where user_id = ? or owner_id = ?",
            List.of(",")
        );

        assertThatThrownBy(() -> provider.authorizedScopes("user-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one");
    }

    @Test
    void appliesConfiguredTimeoutToAuthorizationQuery() {
        SqlClient sqlClient = mock(SqlClient.class);
        String query = "select quota_id, quota_scenes from auth where user_id = ?";
        when(sqlClient.query(query, List.of("user-1"), 7))
            .thenReturn(new QueryResult(List.of(Map.of("quota_id", "A", "quota_scenes", "default"))));
        ConfiguredSqlMetricPermissionProvider provider = new ConfiguredSqlMetricPermissionProvider(
            sqlClient,
            query,
            List.of(","),
            7
        );

        assertThat(provider.authorizedScopes("user-1").scopes())
            .containsExactly(new MetricScope("A", "default"));
        verify(sqlClient).query(query, List.of("user-1"), 7);
    }

    @Test
    void translatesSqlTimeoutFromClient() {
        SqlClient sqlClient = mock(SqlClient.class);
        String query = "select quota_id, quota_scenes from auth where user_id = ?";
        when(sqlClient.query(query, List.of("user-1"), 10))
            .thenThrow(new IllegalStateException(new SQLTimeoutException("timed out")));
        ConfiguredSqlMetricPermissionProvider provider = new ConfiguredSqlMetricPermissionProvider(
            sqlClient,
            query,
            List.of(",")
        );

        assertThatThrownBy(() -> provider.authorizedScopes("user-1"))
            .isInstanceOf(MetricPermissionProviderTimeoutException.class);
    }

    @Test
    void springContextDoesNotCreateProviderWhenAuthorizationQueryIsMissing() {
        new ApplicationContextRunner()
            .withUserConfiguration(ProviderContextConfiguration.class)
            .run(context -> assertThat(context).doesNotHaveBean(ConfiguredSqlMetricPermissionProvider.class));
    }

    @Test
    void springContextCreatesProviderWhenAuthorizationQueryIsConfigured() {
        new ApplicationContextRunner()
            .withUserConfiguration(ProviderContextConfiguration.class)
            .withPropertyValues("database-mcp.permission.metric.provider.authorization-query=select quota_id, quota_scenes from auth where user_id = ?")
            .run(context -> assertThat(context).hasSingleBean(ConfiguredSqlMetricPermissionProvider.class));
    }

    @Configuration
    @EnableConfigurationProperties(DatabaseMcpProperties.class)
    @Import(ConfiguredSqlMetricPermissionProvider.class)
    static class ProviderContextConfiguration {

        @Bean
        SqlClient sqlClient() {
            return mock(SqlClient.class);
        }
    }
}
