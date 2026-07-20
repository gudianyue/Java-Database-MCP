package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.databasemcp.config.DatabaseMcpProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

class SqlAuthorizationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(BaseConfiguration.class);

    @Test
    void disabledAuthorizationStartsWithoutAuthorizer() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void enabledAuthorizationFailsStartupWithoutAuthorizer() {
        contextRunner
            .withPropertyValues("database-mcp.permission.enabled=true")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("exactly one SqlAuthorizer must be configured"));
    }

    @Test
    void enabledAuthorizationStartsWithOneAuthorizer() {
        contextRunner
            .withUserConfiguration(OneAuthorizerConfiguration.class)
            .withPropertyValues("database-mcp.permission.enabled=true")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void enabledAuthorizationFailsStartupWithMultipleAuthorizers() {
        contextRunner
            .withUserConfiguration(OneAuthorizerConfiguration.class, SecondAuthorizerConfiguration.class)
            .withPropertyValues("database-mcp.permission.enabled=true")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("exactly one SqlAuthorizer must be configured"));
    }

    @Test
    void disabledAuthorizationStartsWithMultipleAuthorizers() {
        contextRunner
            .withUserConfiguration(OneAuthorizerConfiguration.class, SecondAuthorizerConfiguration.class)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void httpUrlCreatesAuthorizerWithDefaultTimeoutAndConfiguredHeaders() {
        contextRunner
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.http.url=http://127.0.0.1:8080/authorize",
                "database-mcp.permission.http.headers[Authorization]=Bearer secret"
            )
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(HttpSqlAuthorizer.class);
                DatabaseMcpProperties properties = context.getBean(DatabaseMcpProperties.class);
                assertThat(properties.getPermission().getHttp().getTimeout()).isEqualTo(Duration.ofSeconds(3));
                assertThat(properties.getPermission().getHttp().getHeaders())
                    .containsEntry("Authorization", "Bearer secret");
            });
    }

    @Test
    void httpAuthorizerFailsStartupWhenTimeoutIsNotPositive() {
        contextRunner
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.http.url=http://127.0.0.1:8080/authorize",
                "database-mcp.permission.http.timeout=0s"
            )
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("database-mcp.permission.http.timeout must be greater than zero"));
    }

    @Test
    void httpAndCustomAuthorizersFailStartupTogether() {
        contextRunner
            .withUserConfiguration(OneAuthorizerConfiguration.class)
            .withPropertyValues(
                "database-mcp.permission.enabled=true",
                "database-mcp.permission.http.url=http://127.0.0.1:8080/authorize"
            )
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("exactly one SqlAuthorizer must be configured"));
    }

    @Configuration
    @EnableConfigurationProperties(DatabaseMcpProperties.class)
    @Import({ HttpSqlAuthorizer.class, SqlAuthorizationEnforcer.class })
    static class BaseConfiguration {

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
    static class OneAuthorizerConfiguration {

        @Bean
        @Primary
        SqlAuthorizer firstAuthorizer() {
            return (userId, sql) -> true;
        }
    }

    @Configuration
    static class SecondAuthorizerConfiguration {

        @Bean
        SqlAuthorizer secondAuthorizer() {
            return (userId, sql) -> true;
        }
    }
}
