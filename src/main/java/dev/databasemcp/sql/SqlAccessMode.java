package dev.databasemcp.sql;

/** SQL 访问模式：restricted 只读事务加白名单，unrestricted 无限制。 */
public enum SqlAccessMode {
    UNRESTRICTED,
    RESTRICTED
}
