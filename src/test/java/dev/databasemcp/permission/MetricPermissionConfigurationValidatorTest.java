package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.databasemcp.config.DatabaseMcpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;

class MetricPermissionConfigurationValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ValidatorConfiguration.class);

    @Test
    void startsWhenMetricPermissionIsDisabled() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startsWhenMetricPermissionIsDisabledWithMultipleProviders() {
        contextRunner
            .withUserConfiguration(SecondProviderConfiguration.class)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void ignoresMetricCacheConfigurationWhenMetricAuthorizerIsDisabled() {
        contextRunner
            .withPropertyValues(
                "database-mcp.permission.metric.provider.cache.enabled=true",
                "database-mcp.permission.metric.provider.cache.ttl-seconds=0"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsStartupWhenEnabledConfigurationIsIncomplete() {
        contextRunner
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.metric.enabled=true"
            )
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("protected-tables"));
    }

    @Test
    void failsStartupWhenMultipleProvidersAreRegistered() {
        contextRunner
            .withUserConfiguration(SecondProviderConfiguration.class)
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.metric.enabled=true",
                "database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data",
                "database-mcp.permission.metric.metric-columns[0]=quota_id",
                "database-mcp.permission.metric.scene-columns[0]=quota_scene"
            )
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("exactly one MetricPermissionProvider"));
    }

    @Test
    void startsWithCompleteConfigurationAndOneProvider() {
        contextRunner
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.metric.enabled=true",
                "database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data",
                "database-mcp.permission.metric.metric-columns[0]=quota_id",
                "database-mcp.permission.metric.scene-columns[0]=quota_scene"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsStartupWhenMetricAndCustomAuthorizersAreBothSelected() {
        contextRunner
            .withUserConfiguration(CustomAuthorizerConfiguration.class)
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.metric.enabled=true",
                "database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data",
                "database-mcp.permission.metric.metric-columns[0]=quota_id",
                "database-mcp.permission.metric.scene-columns[0]=quota_scene"
            )
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("exactly one SqlAuthorizer must be configured"));
    }

    @Test
    void failsStartupWhenMetricAndHttpAuthorizersAreBothSelected() {
        contextRunner
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.http.url=http://127.0.0.1:8080/authorize",
                "database-mcp.permission.metric.enabled=true",
                "database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data",
                "database-mcp.permission.metric.metric-columns[0]=quota_id",
                "database-mcp.permission.metric.scene-columns[0]=quota_scene"
            )
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("exactly one SqlAuthorizer must be configured"));
    }

    @Test
    void failsStartupWhenEnabledCacheTtlIsNotPositive() {
        contextRunner
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.metric.enabled=true",
                "database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data",
                "database-mcp.permission.metric.metric-columns[0]=quota_id",
                "database-mcp.permission.metric.scene-columns[0]=quota_scene",
                "database-mcp.permission.metric.provider.cache.enabled=true",
                "database-mcp.permission.metric.provider.cache.ttl-seconds=0"
            )
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("cache.ttl-seconds"));
    }

    @Test
    void failsStartupWhenEnabledCacheKeyPrefixIsBlank() {
        contextRunner
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.metric.enabled=true",
                "database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data",
                "database-mcp.permission.metric.metric-columns[0]=quota_id",
                "database-mcp.permission.metric.scene-columns[0]=quota_scene",
                "database-mcp.permission.metric.provider.cache.enabled=true",
                "database-mcp.permission.metric.provider.cache.key-prefix=   "
            )
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("cache.key-prefix"));
    }

    @Test
    void failsStartupWhenProtectedTablesContainOnlyBlankValues() {
        assertBlankCollectionFails("protected-tables", "protected-tables");
    }

    @Test
    void failsStartupWhenMetricColumnsContainOnlyBlankValues() {
        assertBlankCollectionFails("metric-columns", "metric-columns");
    }

    @Test
    void failsStartupWhenSceneColumnsContainOnlyBlankValues() {
        assertBlankCollectionFails("scene-columns", "scene-columns");
    }

    private void assertBlankCollectionFails(String blankProperty, String expectedMessage) {
        contextRunner
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.metric.enabled=true",
                "database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data",
                "database-mcp.permission.metric.metric-columns[0]=quota_id",
                "database-mcp.permission.metric.scene-columns[0]=quota_scene",
                "database-mcp.permission.metric." + blankProperty + "[0]=   "
            )
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining(expectedMessage));
    }

    @Configuration
    @EnableConfigurationProperties(DatabaseMcpProperties.class)
    @Import({
        ConservativeMetricSqlInspector.class,
        HttpSqlAuthorizer.class,
        MetricPermissionConfigurationValidator.class,
        MetricPermissionEnforcer.class,
        SqlAuthorizationEnforcer.class
    })
    static class ValidatorConfiguration {

        @Bean
        MetricPermissionProvider metricPermissionProvider() {
            return userId -> PermissionScope.empty();
        }

        @Bean
        WebClient.Builder webClientBuilder() {
            return WebClient.builder();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Configuration
    static class SecondProviderConfiguration {

        @Bean
        MetricPermissionProvider secondMetricPermissionProvider() {
            return userId -> PermissionScope.empty();
        }
    }

    @Configuration
    static class CustomAuthorizerConfiguration {

        @Bean
        SqlAuthorizer customSqlAuthorizer() {
            return (userId, sql) -> true;
        }
    }
}
