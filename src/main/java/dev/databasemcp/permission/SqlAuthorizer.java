package dev.databasemcp.permission;

@FunctionalInterface
public interface SqlAuthorizer {

    boolean isAllowed(String userId, String sql);
}
