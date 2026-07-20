package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;

import dev.databasemcp.config.DatabaseMcpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class SqlAuthorizationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(BaseConfiguration.class);

    @Test
    void enabledAuthorizationFailsStartupWithoutAuthorizer() {
        contextRunner
            .withPropertyValues("database-mcp.permission.enabled=true")
            .run(context -> assertThat(context.getStartupFailure())
                .hasMessageContaining("exactly one SqlAuthorizer"));
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
                .hasMessageContaining("exactly one SqlAuthorizer"));
    }

    @Test
    void disabledAuthorizationStartsWithMultipleAuthorizers() {
        contextRunner
            .withUserConfiguration(OneAuthorizerConfiguration.class, SecondAuthorizerConfiguration.class)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    @EnableConfigurationProperties(DatabaseMcpProperties.class)
    @Import({ SqlAuthorizationConfigurationValidator.class, SqlAuthorizationEnforcer.class })
    static class BaseConfiguration {
    }

    @Configuration
    static class OneAuthorizerConfiguration {

        @Bean
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
