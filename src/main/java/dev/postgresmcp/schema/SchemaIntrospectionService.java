package dev.postgresmcp.schema;

import dev.postgresmcp.sql.QueryResult;
import dev.postgresmcp.sql.SqlClient;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SchemaIntrospectionService {

    private final SqlClient sqlClient;

    public SchemaIntrospectionService(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public QueryResult listSchemas() {
        return sqlClient.query("""
            SELECT
                schema_name,
                schema_owner,
                CASE
                    WHEN schema_name LIKE 'pg_%' THEN 'System Schema'
                    WHEN schema_name = 'information_schema' THEN 'System Information Schema'
                    ELSE 'User Schema'
                END as schema_type
            FROM information_schema.schemata
            ORDER BY schema_type, schema_name
            """);
    }

    public QueryResult listObjects(String schemaName, String objectType) {
        return switch (objectType == null ? "table" : objectType) {
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

    public QueryResult getObjectDetails(String schemaName, String objectName, String objectType) {
        return switch (objectType == null ? "table" : objectType) {
            case "table", "view" -> tableOrViewDetails(schemaName, objectName);
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

    private QueryResult tableOrViewDetails(String schemaName, String objectName) {
        return sqlClient.query("""
            WITH columns AS (
                SELECT json_agg(json_build_object(
                    'column', column_name,
                    'data_type', data_type,
                    'is_nullable', is_nullable,
                    'default', column_default
                ) ORDER BY ordinal_position) AS columns
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
            ),
            constraints AS (
                SELECT json_agg(json_build_object(
                    'name', constraint_name,
                    'type', constraint_type,
                    'columns', columns
                )) AS constraints
                FROM (
                    SELECT
                        tc.constraint_name,
                        tc.constraint_type,
                        json_agg(kcu.column_name ORDER BY kcu.ordinal_position) FILTER (WHERE kcu.column_name IS NOT NULL) AS columns
                    FROM information_schema.table_constraints tc
                    LEFT JOIN information_schema.key_column_usage kcu
                        ON tc.constraint_name = kcu.constraint_name
                       AND tc.table_schema = kcu.table_schema
                    WHERE tc.table_schema = ? AND tc.table_name = ?
                    GROUP BY tc.constraint_name, tc.constraint_type
                ) grouped_constraints
            ),
            indexes AS (
                SELECT json_agg(json_build_object('name', indexname, 'definition', indexdef)) AS indexes
                FROM pg_indexes
                WHERE schemaname = ? AND tablename = ?
            )
            SELECT
                ? AS schema,
                ? AS name,
                COALESCE(columns.columns, '[]'::json) AS columns,
                COALESCE(constraints.constraints, '[]'::json) AS constraints,
                COALESCE(indexes.indexes, '[]'::json) AS indexes
            FROM columns, constraints, indexes
            """, List.of(schemaName, objectName, schemaName, objectName, schemaName, objectName, schemaName, objectName));
    }
}
