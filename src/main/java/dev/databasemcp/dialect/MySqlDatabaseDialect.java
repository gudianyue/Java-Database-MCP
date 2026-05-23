package dev.databasemcp.dialect;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MySqlDatabaseDialect implements DatabaseDialect {

    private final SqlClient sqlClient;

    public MySqlDatabaseDialect(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.MYSQL;
    }

    @Override
    public QueryResult listSchemas() {
        return sqlClient.query("""
            SELECT
                SCHEMA_NAME AS schema_name,
                DEFAULT_CHARACTER_SET_NAME AS default_character_set,
                DEFAULT_COLLATION_NAME AS default_collation,
                CASE
                    WHEN SCHEMA_NAME IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                        THEN 'System Schema'
                    ELSE 'User Schema'
                END AS schema_type
            FROM information_schema.SCHEMATA
            ORDER BY schema_type, schema_name
            """);
    }

    @Override
    public QueryResult listObjects(String schemaName, String objectType) {
        return switch (objectType) {
            case "table" -> listTables(schemaName, "BASE TABLE");
            case "view" -> listTables(schemaName, "VIEW");
            case "sequence" -> throw new UnsupportedOperationException(
                "MySQL 不支持 PostgreSQL 风格的 sequence 对象。"
            );
            case "extension" -> throw new UnsupportedOperationException(
                "MySQL 不支持 PostgreSQL 风格的 extension 对象。"
            );
            default -> throw new IllegalArgumentException("不支持的对象类型：" + objectType);
        };
    }

    @Override
    public QueryResult getObjectDetails(String schemaName, String objectName, String objectType) {
        return switch (objectType) {
            case "table", "view" -> sqlClient.query("""
                SELECT
                    c.TABLE_SCHEMA AS schema_name,
                    c.TABLE_NAME AS object_name,
                    c.COLUMN_NAME AS column_name,
                    c.DATA_TYPE AS data_type,
                    c.IS_NULLABLE AS is_nullable,
                    c.COLUMN_DEFAULT AS column_default,
                    c.COLUMN_KEY AS column_key,
                    kcu.CONSTRAINT_NAME AS constraint_name,
                    kcu.REFERENCED_TABLE_SCHEMA AS referenced_table_schema,
                    kcu.REFERENCED_TABLE_NAME AS referenced_table_name,
                    kcu.REFERENCED_COLUMN_NAME AS referenced_column_name,
                    s.INDEX_NAME AS index_name,
                    s.NON_UNIQUE AS non_unique,
                    s.SEQ_IN_INDEX AS sequence_in_index
                FROM information_schema.COLUMNS c
                LEFT JOIN information_schema.KEY_COLUMN_USAGE kcu
                    ON c.TABLE_SCHEMA = kcu.TABLE_SCHEMA
                   AND c.TABLE_NAME = kcu.TABLE_NAME
                   AND c.COLUMN_NAME = kcu.COLUMN_NAME
                LEFT JOIN information_schema.STATISTICS s
                    ON c.TABLE_SCHEMA = s.TABLE_SCHEMA
                   AND c.TABLE_NAME = s.TABLE_NAME
                   AND c.COLUMN_NAME = s.COLUMN_NAME
                WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ?
                ORDER BY c.ORDINAL_POSITION, s.INDEX_NAME, s.SEQ_IN_INDEX
                """, List.of(schemaName, objectName));
            case "sequence" -> throw new UnsupportedOperationException(
                "MySQL 不支持 PostgreSQL 风格的 sequence 对象。"
            );
            case "extension" -> throw new UnsupportedOperationException(
                "MySQL 不支持 PostgreSQL 风格的 extension 对象。"
            );
            default -> throw new IllegalArgumentException("不支持的对象类型：" + objectType);
        };
    }

    @Override
    public QueryResult explain(String sql) {
        return sqlClient.query("EXPLAIN " + sql);
    }

    private QueryResult listTables(String schemaName, String tableType) {
        return sqlClient.query("""
            SELECT
                TABLE_SCHEMA AS table_schema,
                TABLE_NAME AS table_name,
                TABLE_TYPE AS table_type
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = ?
            ORDER BY table_name
            """, List.of(schemaName, tableType));
    }
}
