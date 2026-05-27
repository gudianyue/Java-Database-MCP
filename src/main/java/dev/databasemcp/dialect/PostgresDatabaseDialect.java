package dev.databasemcp.dialect;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PostgresDatabaseDialect implements DatabaseDialect {

    private final SqlClient sqlClient;

    public PostgresDatabaseDialect(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.POSTGRESQL;
    }

    @Override
    public QueryResult listSchemas() {
        return sqlClient.query("""
            SELECT
                schema_name,
                schema_owner,
                CASE
                    WHEN schema_name LIKE 'pg_%' THEN 'System Schema'
                    WHEN schema_name = 'information_schema' THEN 'System Information Schema'
                    ELSE 'User Schema'
                END AS schema_type
            FROM information_schema.schemata
            ORDER BY schema_type, schema_name
            """);
    }

    @Override
    public QueryResult listObjects(String schemaName, String objectType) {
        return switch (objectType) {
            case "table" -> sqlClient.query("""
                SELECT table_schema, table_name, table_type
                FROM information_schema.tables
                WHERE table_schema = ? AND table_type = ?
                ORDER BY table_name
                """, List.of(schemaName, "BASE TABLE"));
            case "view" -> sqlClient.query("""
                SELECT table_schema, table_name, table_type
                FROM information_schema.tables
                WHERE table_schema = ? AND table_type = ?
                ORDER BY table_name
                """, List.of(schemaName, "VIEW"));
            case "sequence" -> sqlClient.query("""
                SELECT sequence_schema, sequence_name, data_type
                FROM information_schema.sequences
                WHERE sequence_schema = ?
                ORDER BY sequence_name
                """, List.of(schemaName));
            case "extension" -> sqlClient.query("""
                SELECT extname, extversion, extrelocatable
                FROM pg_extension
                ORDER BY extname
                """);
            default -> throw new IllegalArgumentException("不支持的对象类型：" + objectType);
        };
    }

    @Override
    public QueryResult getObjectDetails(String schemaName, String objectName, String objectType) {
        return switch (objectType) {
            case "table", "view" -> sqlClient.query("""
                SELECT
                    n.nspname AS schema,
                    c.relname AS name,
                    COALESCE(
                        (SELECT json_agg(json_build_object(
                            'column', a.attname,
                            'data_type', pg_catalog.format_type(a.atttypid, a.atttypmod),
                            'is_nullable', CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END,
                            'default', pg_catalog.pg_get_expr(d.adbin, d.adrelid)
                        ) ORDER BY a.attnum)
                        FROM pg_catalog.pg_attribute a
                        LEFT JOIN pg_catalog.pg_attrdef d ON a.attrelid = d.adrelid AND a.attnum = d.adnum
                        WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped),
                        '[]'::json
                    ) AS columns,
                    COALESCE(
                        (SELECT json_agg(json_build_object(
                            'name', con.conname,
                            'type', CASE con.contype
                                WHEN 'p' THEN 'PRIMARY KEY'
                                WHEN 'u' THEN 'UNIQUE'
                                WHEN 'f' THEN 'FOREIGN KEY'
                                WHEN 'c' THEN 'CHECK'
                            END,
                            'columns', (SELECT json_agg(att.attname ORDER BY s.i)
                                FROM unnest(con.conkey) WITH ORDINALITY AS s(attnum, i)
                                JOIN pg_catalog.pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = s.attnum)
                        ) ORDER BY con.conname) FILTER (WHERE con.conname IS NOT NULL)
                        FROM pg_catalog.pg_constraint con
                        WHERE con.conrelid = c.oid),
                        '[]'::json
                    ) AS constraints,
                    COALESCE(
                        (SELECT json_agg(json_build_object(
                            'name', ix_cls.relname,
                            'definition', pg_catalog.pg_get_indexdef(ix.indexrelid, 0, true)
                        ))
                        FROM pg_catalog.pg_index ix
                        JOIN pg_catalog.pg_class ix_cls ON ix.indexrelid = ix_cls.oid
                        WHERE ix.indrelid = c.oid),
                        '[]'::json
                    ) AS indexes
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = ? AND c.relname = ?
                """, List.of(schemaName, objectName));
            case "sequence" -> sqlClient.query("""
                SELECT sequence_schema, sequence_name, data_type, start_value, increment
                FROM information_schema.sequences
                WHERE sequence_schema = ? AND sequence_name = ?
                """, List.of(schemaName, objectName));
            case "extension" -> sqlClient.query("""
                SELECT extname, extversion, extrelocatable
                FROM pg_extension
                WHERE extname = ?
                """, List.of(objectName));
            default -> throw new IllegalArgumentException("不支持的对象类型：" + objectType);
        };
    }

    @Override
    public QueryResult explain(String sql) {
        return sqlClient.query("EXPLAIN (FORMAT TEXT) " + sql);
    }
}
