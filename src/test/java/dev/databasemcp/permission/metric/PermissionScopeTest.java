package dev.databasemcp.permission.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionScopeTest {

    @Test
    void keepsMetricScopeTupleSemantics() {
        PermissionScope scope = new PermissionScope(Set.of(
            new MetricScope("A", "default"),
            new MetricScope("B", "custom")
        ));

        assertThat(scope.containsAll(Set.of(
            new MetricScope("A", "default"),
            new MetricScope("B", "custom")
        ))).isTrue();
        assertThat(scope.containsAll(Set.of(new MetricScope("A", "custom")))).isFalse();
    }
}
