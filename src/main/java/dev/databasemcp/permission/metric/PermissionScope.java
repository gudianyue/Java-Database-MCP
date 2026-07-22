package dev.databasemcp.permission.metric;

import java.util.Collection;
import java.util.Set;

/** 某用户被授权的指标范围集合。 */
public record PermissionScope(Set<MetricScope> scopes) {

    public PermissionScope {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public static PermissionScope empty() {
        return new PermissionScope(Set.of());
    }

    public boolean containsAll(Collection<MetricScope> requestedScopes) {
        return scopes.containsAll(requestedScopes);
    }
}
