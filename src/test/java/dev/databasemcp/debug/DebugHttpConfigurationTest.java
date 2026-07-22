package dev.databasemcp.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.databasemcp.mcp.DatabaseToolFacade;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DebugHttpConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(DebugHttpAdapter.class)
        .withBean(DatabaseToolFacade.class, () -> mock(DatabaseToolFacade.class));

    @Test
    void disabledByDefaultControllerNotLoaded() {
        runner.run(context -> assertThat(context).doesNotHaveBean(DebugHttpAdapter.class));
    }

    @Test
    void enabledControllerLoaded() {
        runner.withPropertyValues("database-mcp.debug.http.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(DebugHttpAdapter.class));
    }

    private final ApplicationContextRunner guardRunner = new ApplicationContextRunner()
        .withUserConfiguration(DebugHttpLoopbackGuard.class);

    @Test
    void enabledAndNonLoopbackRefusesToStart() {
        guardRunner
            .withPropertyValues("database-mcp.debug.http.enabled=true", "server.address=0.0.0.0")
            .run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                while (failure.getCause() != null) {
                    failure = failure.getCause();
                }
                assertThat(failure).hasMessageContaining("loopback");
            });
    }

    @Test
    void enabledAndLoopbackStarts() {
        guardRunner
            .withPropertyValues("database-mcp.debug.http.enabled=true", "server.address=127.0.0.1")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void enabledWithoutExplicitServerAddressStarts() {
        guardRunner
            .withPropertyValues("database-mcp.debug.http.enabled=true")
            .run(context -> assertThat(context).hasNotFailed());
    }
}
