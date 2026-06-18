package dev.databasemcp.dialect;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.diagnostics.ReadOnlyQueryValidator;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DamengDatabaseDialect implements DatabaseDialect {

    private final SqlClient sqlClient;

    public DamengDatabaseDialect(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.DAMENG;
    }

    @Override
    public QueryResult listSchemas() {
        return sqlClient.query("""
            SELECT
                USERNAME AS schema_name,
                CASE
                    WHEN USERNAME IN ('SYS', 'SYSDBA', 'SYSAUDITOR', 'SYSSSO')
                        OR USERNAME LIKE 'SYS%'
                        THEN '系统 Schema'
                    ELSE '用户 Schema'
                END AS schema_type
            FROM ALL_USERS
            ORDER BY schema_type, schema_name
            """);
    }

    @Override
    public QueryResult listObjects(String schemaName, String objectType) {
        return switch (objectType) {
            case "table" -> sqlClient.query("""
                SELECT
                    OWNER AS table_schema,
                    TABLE_NAME AS table_name,
                    'BASE TABLE' AS table_type
                FROM ALL_TABLES
                WHERE OWNER = ?
                ORDER BY TABLE_NAME
                """, List.of(schemaName));
            case "view" -> sqlClient.query("""
                SELECT
                    OWNER AS table_schema,
                    VIEW_NAME AS table_name,
                    'VIEW' AS table_type
                FROM ALL_VIEWS
                WHERE OWNER = ?
                ORDER BY VIEW_NAME
                """, List.of(schemaName));
            case "sequence" -> sqlClient.query("""
                SELECT
                    SEQUENCE_OWNER AS sequence_schema,
                    SEQUENCE_NAME AS sequence_name,
                    INCREMENT_BY AS increment_by
                FROM ALL_SEQUENCES
                WHERE SEQUENCE_OWNER = ?
                ORDER BY SEQUENCE_NAME
                """, List.of(schemaName));
            case "extension" -> throw new UnsupportedOperationException(
                "达梦不支持 PostgreSQL 风格的 extension 对象。"
            );
            default -> throw new IllegalArgumentException("不支持的对象类型：" + objectType);
        };
    }

    @Override
    public QueryResult getObjectDetails(String schemaName, String objectName, String objectType) {
        return switch (objectType) {
            case "table", "view" -> sqlClient.query("""
                SELECT
                    'COLUMN' AS detail_type,
                    c.OWNER AS schema_name,
                    c.TABLE_NAME AS object_name,
                    c.COLUMN_NAME AS name,
                    c.DATA_TYPE AS data_type,
                    c.NULLABLE AS nullable,
                    CAST(c.DATA_DEFAULT AS VARCHAR(4000)) AS definition,
                    c.COLUMN_ID AS position
                FROM ALL_TAB_COLUMNS c
                WHERE c.OWNER = ? AND c.TABLE_NAME = ?
                UNION ALL
                SELECT
                    'CONSTRAINT' AS detail_type,
                    con.OWNER AS schema_name,
                    con.TABLE_NAME AS object_name,
                    con.CONSTRAINT_NAME AS name,
                    con.CONSTRAINT_TYPE AS data_type,
                    con.STATUS AS nullable,
                    con.SEARCH_CONDITION AS definition,
                    100000 AS position
                FROM ALL_CONSTRAINTS con
                WHERE con.OWNER = ? AND con.TABLE_NAME = ?
                UNION ALL
                SELECT
                    'INDEX' AS detail_type,
                    idx.OWNER AS schema_name,
                    idx.TABLE_NAME AS object_name,
                    idx.INDEX_NAME AS name,
                    idx.UNIQUENESS AS data_type,
                    idx.STATUS AS nullable,
                    idx.INDEX_TYPE AS definition,
                    200000 AS position
                FROM ALL_INDEXES idx
                WHERE idx.OWNER = ? AND idx.TABLE_NAME = ?
                ORDER BY detail_type, position, name
                """, List.of(schemaName, objectName, schemaName, objectName, schemaName, objectName));
            case "sequence" -> sqlClient.query("""
                SELECT
                    SEQUENCE_OWNER AS schema_name,
                    SEQUENCE_NAME AS object_name,
                    MIN_VALUE AS min_value,
                    MAX_VALUE AS max_value,
                    INCREMENT_BY AS increment_by,
                    CYCLE_FLAG AS cycle_flag,
                    ORDER_FLAG AS order_flag,
                    CACHE_SIZE AS cache_size
                FROM ALL_SEQUENCES
                WHERE SEQUENCE_OWNER = ? AND SEQUENCE_NAME = ?
                """, List.of(schemaName, objectName));
            case "extension" -> throw new UnsupportedOperationException(
                "达梦不支持 PostgreSQL 风格的 extension 对象。"
            );
            default -> throw new IllegalArgumentException("不支持的对象类型：" + objectType);
        };
    }

    @Override
    public QueryResult explain(String sql) {
        ReadOnlyQueryValidator.validateSelectSingleStatement(sql);
        return sqlClient.query("EXPLAIN " + sql);
    }
}
