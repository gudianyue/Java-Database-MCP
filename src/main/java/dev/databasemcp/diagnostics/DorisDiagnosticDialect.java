package dev.databasemcp.diagnostics;

import static dev.databasemcp.diagnostics.DiagnosticSupport.joinRows;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SecretMasker;
import dev.databasemcp.sql.SqlClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Doris 诊断方言，基于 __internal_schema.audit_log 和 information_schema.BACKENDS
 * 实现慢查询统计、健康检查和索引建议。
 */
@Component
public class DorisDiagnosticDialect implements DiagnosticDialect {

    private static final Set<String> TOP_QUERY_SORT_BY = Set.of("mean_time", "total_time", "executions");
    private static final Set<String> DORIS_HEALTH_TYPES = Set.of(
        "doris_audit_log", "doris_compaction", "doris_tablet_health", "all"
    );
    private static final Set<String> LEGACY_HEALTH_TYPES = Set.of(
        "vacuum", "fragmentation", "sequence", "auto_increment",
        "wait", "storage", "replication", "index", "connection", "buffer", "constraint"
    );

    private final SqlClient sqlClient;

    public DorisDiagnosticDialect(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.DORIS;
    }

    @Override
    public String getTopQueries(String sortBy, int limit) {
        String effectiveSortBy = normalizeTopQuerySort(sortBy);
        int effectiveLimit = limit <= 0 ? 10 : limit;
        String orderBy = switch (effectiveSortBy) {
            case "mean_time" -> "avg_query_time_ms";
            case "executions" -> "query_count";
            default -> "total_query_time_ms";
        };
        try {
            QueryResult result = sqlClient.query("""
                SELECT *
                FROM (
                    SELECT
                        COALESCE(stmt, digest) AS query,
                        COUNT(*) AS query_count,
                        SUM(query_time_ms) AS total_query_time_ms,
                        AVG(query_time_ms) AS avg_query_time_ms
                    FROM __internal_schema.audit_log
                    WHERE COALESCE(stmt, digest) IS NOT NULL
                    GROUP BY COALESCE(stmt, digest)
                    ORDER BY %s DESC
                )
                WHERE ROWNUM <= ?
                """.formatted(orderBy), List.of(effectiveLimit));
            return "Doris 高消耗查询（sort_by=" + effectiveSortBy + ", limit=" + effectiveLimit + "）\n" + renderRows(result);
        } catch (RuntimeException e) {
            throw new RuntimeException("高消耗查询失败：" + SecretMasker.mask(e.getMessage()), e);
        }
    }

    @Override
    public String analyzeHealth(String healthType) {
        List<String> requested = normalizeHealthTypes(healthType);
        List<String> sections = new ArrayList<>();
        for (String type : requested) {
            if (LEGACY_HEALTH_TYPES.contains(type)) {
                throw new UnsupportedOperationException(legacyUnsupportedMessage(type));
            }
            if (!DORIS_HEALTH_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                    "不支持的 Doris 健康检查类型：'" + type + "'。Doris 仅支持："
                        + String.join(", ", DORIS_HEALTH_TYPES) + "。"
                );
            }
            // normalizeHealthTypes 已将 "all" 展开为 DORIS_HEALTH_TYPES 中的各原语，
            // 且非 DORIS_HEALTH_TYPES 的值已被前面的检查抛错，此处 switch 不需要 default 分支。
            switch (type) {
                case "doris_audit_log" -> sections.add(runCheck("审计日志健康", this::dorisAuditLog));
                case "doris_compaction" -> sections.add(runCheck("Compaction 健康", this::dorisCompaction));
                case "doris_tablet_health" -> sections.add(runCheck("Tablet 健康", this::dorisTabletHealth));
                default -> throw new IllegalStateException("未覆盖的 Doris 健康检查类型：" + type);
            }
        }
        return String.join("\n\n", sections);
    }

    @Override
    public String analyzeWorkloadIndexes(int maxIndexSizeMb, String method) {
        try {
            QueryResult result = sqlClient.query("""
                SELECT *
                FROM (
                    SELECT
                        COALESCE(stmt, digest) AS query,
                        COUNT(*) AS executions,
                        SUM(query_time_ms) AS total_query_time_ms
                    FROM __internal_schema.audit_log
                    WHERE COALESCE(stmt, digest) IS NOT NULL
                    GROUP BY COALESCE(stmt, digest)
                    ORDER BY total_query_time_ms DESC
                )
                WHERE ROWNUM <= 10
                """);
            return "Doris 工作负载索引建议（method=dta, max_index_size_mb=" + maxIndexSizeMb + "）\n"
                + renderRows(result)
                + "\n建议依据：检查高成本 SELECT 的过滤列和连接列，结合 information_schema.COLUMNS 评估列基数。";
        } catch (RuntimeException e) {
            return degraded("工作负载索引建议", e);
        }
    }

    @Override
    public String analyzeQueryIndexes(List<String> queries, int maxIndexSizeMb, String method) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("queries 不能为空");
        }
        if (queries.size() > 10) {
            throw new IllegalArgumentException("queries 最多支持 10 条 SQL 语句");
        }
        for (String query : queries) {
            ReadOnlyQueryValidator.validateSelectSingleStatement(query);
        }
        StringBuilder builder = new StringBuilder(
            "Doris 查询索引建议（method=dta, max_index_size_mb=" + maxIndexSizeMb + "）"
        );
        for (String query : queries) {
            builder.append("\n\n查询：").append(query);
            try {
                builder.append("\n执行计划：\n").append(renderRows(sqlClient.query("EXPLAIN " + query)));
            } catch (RuntimeException e) {
                builder.append("\n执行计划不可用：").append(SecretMasker.mask(e.getMessage()));
            }
            builder.append("\n建议依据：检查过滤列、连接列和已有索引情况，评估在 Doris 列存模型下的索引收益。");
        }
        return builder.toString();
    }

    private String dorisAuditLog() {
        QueryResult result = sqlClient.query("""
            SELECT
                `user` AS user_name,
                COALESCE(stmt, digest) AS query,
                COUNT(*) AS query_count,
                MAX(timestamp) AS last_active
            FROM __internal_schema.audit_log
            WHERE `user` IS NOT NULL
            GROUP BY `user`, COALESCE(stmt, digest)
            ORDER BY query_count DESC
            LIMIT 20
            """);
        return renderRowsOrHealthy(result, "当前用户未看到审计日志记录。");
    }

    private String dorisCompaction() {
        QueryResult result = sqlClient.query("""
            SELECT
                backend_id,
                host,
                alive,
                tablet_num,
                last_heartbeat
            FROM information_schema.BACKENDS
            ORDER BY backend_id
            """);
        return renderRows(result);
    }

    private String dorisTabletHealth() {
        QueryResult result = sqlClient.query("""
            SELECT
                tablet_id,
                backend_id,
                version_count,
                row_count,
                last_check_time
            FROM information_schema.tablets
            ORDER BY tablet_id
            LIMIT 50
            """);
        return renderRowsOrHealthy(result, "当前未发现 tablet 记录。");
    }

    private String runCheck(String title, Supplier<String> check) {
        try {
            return "## " + title + "\n" + check.get();
        } catch (RuntimeException e) {
            return "## " + title + "\n" + degraded(title, e);
        }
    }

    private static String renderRowsOrHealthy(QueryResult result, String healthyMessage) {
        return result.rows().isEmpty() ? healthyMessage : renderRows(result);
    }

    private static String renderRows(QueryResult result) {
        return result.rows().isEmpty() ? "[]" : joinRows(result.rows(), Object::toString);
    }

    private static String degraded(String section, RuntimeException e) {
        return "当前权限或版本无法获取 Doris " + section + "："
            + SecretMasker.mask(e.getMessage());
    }

    private static String legacyUnsupportedMessage(String type) {
        return switch (type) {
            case "vacuum" -> "Doris 不支持 PG 风格的 vacuum 健康检查。";
            case "fragmentation" -> "Doris 不支持 MySQL 风格的 fragmentation 健康检查。";
            case "auto_increment" -> "Doris 不支持 MySQL 风格的 auto_increment 健康检查。";
            case "wait" -> "Doris 不支持 Dameng 风格的 wait 健康检查。";
            case "storage" -> "Doris 不支持 Dameng 风格的 storage 健康检查。";
            case "sequence" -> "Doris 不支持 Dameng/PG 风格的 sequence 健康检查。";
            case "replication" -> "Doris 不支持 PG/MySQL 风格的 replication 健康检查。";
            case "index" -> "Doris 不支持 Dameng 风格的 index 健康检查。";
            case "connection" -> "Doris 不支持通用 connection 健康检查。";
            case "buffer" -> "Doris 不支持通用 buffer 健康检查。";
            case "constraint" -> "Doris 不支持通用 constraint 健康检查。";
            default -> "Doris 不支持 " + type + " 风格的健康检查。";
        };
    }

    private static String normalizeTopQuerySort(String sortBy) {
        String normalized = sortBy == null || sortBy.isBlank() ? "total_time" : sortBy.toLowerCase(Locale.ROOT);
        return TOP_QUERY_SORT_BY.contains(normalized) ? normalized : "total_time";
    }

    private static List<String> normalizeHealthTypes(String healthType) {
        String requested = healthType == null || healthType.isBlank() ? "all" : healthType.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> types = new LinkedHashSet<>();
        List<String> dorisPrimitives = DORIS_HEALTH_TYPES.stream()
            .filter(t -> !"all".equals(t))
            .toList();
        for (String type : requested.split(",")) {
            String trimmed = type.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("all".equals(trimmed)) {
                types.addAll(dorisPrimitives);
            } else {
                types.add(trimmed);
            }
        }
        return new ArrayList<>(types);
    }

}
