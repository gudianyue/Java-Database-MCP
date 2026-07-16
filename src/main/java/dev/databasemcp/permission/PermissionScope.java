package dev.databasemcp.permission;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public record PermissionScope(Set<MetricScope> scopes) {

    public PermissionScope {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public static PermissionScope empty() {
        return new PermissionScope(Set.of());
    }

    public boolean contains(MetricScope scope) {
        return scopes.contains(scope);
    }

    public boolean containsAll(Collection<MetricScope> requestedScopes) {
        return scopes.containsAll(new LinkedHashSet<>(requestedScopes));
    }
}
