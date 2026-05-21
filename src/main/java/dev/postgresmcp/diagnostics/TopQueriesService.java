package dev.postgresmcp.diagnostics;

import dev.postgresmcp.sql.QueryResult;
import dev.postgresmcp.sql.SqlClient;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TopQueriesService {

    private static final String PG_STAT_STATEMENTS = "pg_stat_statements";

    private final SqlClient sqlClient;
    private final PostgresExtensionService extensionService;

    public TopQueriesService(SqlClient sqlClient, PostgresExtensionService extensionService) {
        this.sqlClient = sqlClient;
        this.extensionService = extensionService;
    }

    public String getTopQueries(String sortBy, int limit) {
        String criteria = sortBy == null || sortBy.isBlank() ? "resources" : sortBy;
        if (!extensionService.isExtensionInstalled(PG_STAT_STATEMENTS)) {
            return pgStatStatementsInstallMessage();
        }
        return switch (criteria) {
            case "resources" -> sqlClient.query(resourceQuery(columns())).rows().toString();
            case "mean_time" -> topQueriesByTime(limit, "mean");
            case "total_time" -> topQueriesByTime(limit, "total");
            default -> throw new IllegalArgumentException("无效排序条件。请使用 'resources'、'mean_time' 或 'total_time'。");
        };
    }

    private String topQueriesByTime(int limit, String sortBy) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        PgStatStatementsColumns columns = columns();
        String orderBy = "total".equals(sortBy) ? columns.totalTime() : columns.meanTime();
        QueryResult result = sqlClient.query(
            """
            SELECT
                query,
                calls,
                %s,
                %s,
                rows
            FROM pg_stat_statements
            ORDER BY %s DESC
            LIMIT ?
            """.formatted(columns.totalTime(), columns.meanTime(), orderBy),
            List.of(limit)
        );
        String criteria = "total".equals(sortBy) ? "总执行时间" : "单次平均执行时间";
        List<Map<String, Object>> rows = result.rows();
        return "按" + criteria + "排序的前 " + rows.size() + " 条慢查询：\n" + rows;
    }

    private String resourceQuery(PgStatStatementsColumns columns) {
        return """
            WITH resource_fractions AS (
                SELECT
                    query,
                    calls,
                    rows,
                    %s AS total_exec_time,
                    %s AS mean_exec_time,
                    %s AS stddev_exec_time,
                    shared_blks_hit,
                    shared_blks_read,
                    shared_blks_dirtied,
                    %s,
                    %s / NULLIF(SUM(%s) OVER (), 0) AS total_exec_time_frac,
                    (shared_blks_hit + shared_blks_read)
                        / NULLIF(SUM(shared_blks_hit + shared_blks_read) OVER (), 0)
                        AS shared_blks_accessed_frac,
                    shared_blks_read / NULLIF(SUM(shared_blks_read) OVER (), 0)
                        AS shared_blks_read_frac,
                    shared_blks_dirtied / NULLIF(SUM(shared_blks_dirtied) OVER (), 0)
                        AS shared_blks_dirtied_frac,
                    %s
                FROM pg_stat_statements
            )
            SELECT
                query,
                calls,
                rows,
                total_exec_time,
                mean_exec_time,
                stddev_exec_time,
                total_exec_time_frac,
                shared_blks_accessed_frac,
                shared_blks_read_frac,
                shared_blks_dirtied_frac,
                total_wal_bytes_frac,
                shared_blks_hit,
                shared_blks_read,
                shared_blks_dirtied,
                wal_bytes
            FROM resource_fractions
            WHERE
                total_exec_time_frac > 0.05
                OR shared_blks_accessed_frac > 0.05
                OR shared_blks_read_frac > 0.05
                OR shared_blks_dirtied_frac > 0.05
                OR total_wal_bytes_frac > 0.05
            ORDER BY total_exec_time DESC
            """.formatted(
            columns.totalTime(),
            columns.meanTime(),
            columns.stddevTime(),
            columns.walBytesSelect(),
            columns.totalTime(),
            columns.totalTime(),
            columns.walBytesFraction()
        );
    }

    private PgStatStatementsColumns columns() {
        int majorVersion = extensionService.postgresMajorVersion();
        if (majorVersion >= 13) {
            return new PgStatStatementsColumns(
                "total_exec_time",
                "mean_exec_time",
                "stddev_exec_time",
                "wal_bytes",
                "wal_bytes / NULLIF(SUM(wal_bytes) OVER (), 0) AS total_wal_bytes_frac"
            );
        }
        return new PgStatStatementsColumns(
            "total_time",
            "mean_time",
            "stddev_time",
            "0 AS wal_bytes",
            "0 AS total_wal_bytes_frac"
        );
    }

    private static String pgStatStatementsInstallMessage() {
        return """
            pg_stat_statements 扩展是查询慢 SQL 和资源消耗统计所必需的，但当前数据库尚未安装。

            可以在数据库中执行：CREATE EXTENSION pg_stat_statements;

            该扩展会记录查询次数、执行时间、返回行数和资源访问统计，是 PostgreSQL 性能诊断的常用扩展。
            """.strip();
    }

    private record PgStatStatementsColumns(
        String totalTime,
        String meanTime,
        String stddevTime,
        String walBytesSelect,
        String walBytesFraction
    ) {
    }
}
