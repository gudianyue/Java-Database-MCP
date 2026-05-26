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
                    ? AS schema_name,
                    ? AS object_name,
                    COALESCE(
                        (SELECT JSON_ARRAYAGG(JSON_OBJECT(
                            'column', c.COLUMN_NAME,
                            'data_type', c.DATA_TYPE,
                            'is_nullable', c.IS_NULLABLE,
                            'default', c.COLUMN_DEFAULT,
                            'column_key', c.COLUMN_KEY
                        ) ORDER BY c.ORDINAL_POSITION)
                        FROM information_schema.COLUMNS c
                        WHERE c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME),
                        JSON_ARRAY()
                    ) AS columns,
                    COALESCE(
                        (SELECT JSON_ARRAYAGG(constraint_obj)
                        FROM (
                            SELECT JSON_OBJECT(
                                'name', kcu.CONSTRAINT_NAME,
                                'columns', (SELECT JSON_ARRAYAGG(kcu2.COLUMN_NAME ORDER BY kcu2.ORDINAL_POSITION)
                                    FROM information_schema.KEY_COLUMN_USAGE kcu2
                                    WHERE kcu2.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
                                      AND kcu2.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
                                      AND kcu2.TABLE_SCHEMA = kcu.TABLE_SCHEMA
                                      AND kcu2.TABLE_NAME = kcu.TABLE_NAME),
                                'referenced_table_schema', kcu.REFERENCED_TABLE_SCHEMA,
                                'referenced_table_name', kcu.REFERENCED_TABLE_NAME,
                                'referenced_column_name', kcu.REFERENCED_COLUMN_NAME
                            ) AS constraint_obj
                            FROM information_schema.KEY_COLUMN_USAGE kcu
                            WHERE kcu.TABLE_SCHEMA = t.TABLE_SCHEMA AND kcu.TABLE_NAME = t.TABLE_NAME
                            GROUP BY kcu.CONSTRAINT_SCHEMA, kcu.CONSTRAINT_NAME,
                                kcu.TABLE_SCHEMA, kcu.TABLE_NAME,
                                kcu.REFERENCED_TABLE_SCHEMA, kcu.REFERENCED_TABLE_NAME,
                                kcu.REFERENCED_COLUMN_NAME
                            ORDER BY kcu.CONSTRAINT_NAME
                        ) constraints_sub),
                        JSON_ARRAY()
                    ) AS constraints,
                    COALESCE(
                        (SELECT JSON_ARRAYAGG(JSON_OBJECT(
                            'name', s.INDEX_NAME,
                            'non_unique', s.NON_UNIQUE,
                            'columns', (SELECT JSON_ARRAYAGG(s2.COLUMN_NAME ORDER BY s2.SEQ_IN_INDEX)
                                FROM information_schema.STATISTICS s2
                                WHERE s2.TABLE_SCHEMA = s.TABLE_SCHEMA
                                  AND s2.TABLE_NAME = s.TABLE_NAME
                                  AND s2.INDEX_NAME = s.INDEX_NAME)
                        ))
                        FROM (SELECT DISTINCT s0.TABLE_SCHEMA, s0.TABLE_NAME, s0.INDEX_NAME, s0.NON_UNIQUE
                              FROM information_schema.STATISTICS s0
                              WHERE s0.TABLE_SCHEMA = t.TABLE_SCHEMA AND s0.TABLE_NAME = t.TABLE_NAME
                              ORDER BY s0.INDEX_NAME) s),
                        JSON_ARRAY()
                    ) AS indexes
                FROM information_schema.TABLES t
                WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME = ? AND t.TABLE_TYPE IN ('BASE TABLE', 'VIEW')
                """, List.of(schemaName, objectName, schemaName, objectName));
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
