package dev.databasemcp.permission;

/** SQL 授权器接口，对 userId+SQL 作出允许或拒绝决定；可插拔，容器中恰有一个实现。 */
@FunctionalInterface
public interface SqlAuthorizer {

    boolean isAllowed(String userId, String sql);
}
