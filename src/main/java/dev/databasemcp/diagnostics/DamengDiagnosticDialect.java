package dev.databasemcp.diagnostics;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SecretMasker;
import dev.databasemcp.sql.SqlClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DamengDiagnosticDialect implements DiagnosticDialect {

    private static final Set<String> TOP_QUERY_SORT_BY = Set.of("mean_time", "total_time", "executions");
    private static final Set<String> HEALTH_TYPES = Set.of(
        "index", "connection", "wait", "storage", "sequence", "buffer", "constraint", "all"
    );

    private final SqlClient sqlClient;

    public DamengDiagnosticDialect(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.DAMENG;
    }

    @Override
    public Set<String> supportedTopQuerySortBy() {
        return TOP_QUERY_SORT_BY;
    }

    @Override
    public Set<String> supportedHealthTypes() {
        return HEALTH_TYPES;
    }

    @Override
    public boolean supportsHypotheticalIndexes() {
        return false;
    }

    @Override
    public String getTopQueries(String sortBy, int limit) {
        String effectiveSortBy = normalizeTopQuerySort(sortBy);
        int effectiveLimit = limit <= 0 ? 10 : limit;
        String orderBy = switch (effectiveSortBy) {
            case "mean_time" -> "avg_elapsed_time";
            case "executions" -> "executions";
            default -> "total_elapsed_time";
        };
        try {
            QueryResult result = sqlClient.query("""
                SELECT *
                FROM (
                    SELECT
                        COALESCE(TOP_SQL_TEXT, SEC_SQL_TEXT, THRD_SQL_TEXT) AS query,
                        COUNT(*) AS executions,
                        SUM(TIME_USED) AS total_elapsed_time,
                        AVG(TIME_USED) AS avg_elapsed_time
                    FROM V$SQL_HISTORY
                    WHERE COALESCE(TOP_SQL_TEXT, SEC_SQL_TEXT, THRD_SQL_TEXT) IS NOT NULL
                    GROUP BY COALESCE(TOP_SQL_TEXT, SEC_SQL_TEXT, THRD_SQL_TEXT)
                    ORDER BY %s DESC
                )
                WHERE ROWNUM <= ?
                """.formatted(orderBy), List.of(effectiveLimit));
            return "达梦高消耗查询（sort_by=" + effectiveSortBy + ", limit=" + effectiveLimit + "）\n" + renderRows(result);
        } catch (RuntimeException e) {
            return degraded("高消耗查询", e);
        }
    }

    @Override
    public String analyzeHealth(String healthType) {
        List<String> requested = normalizeHealthTypes(healthType);
        List<String> sections = new ArrayList<>();
        for (String type : requested) {
            switch (type) {
                case "index" -> sections.add(runCheck("索引健康", this::indexHealth));
                case "connection" -> sections.add(runCheck("连接健康", this::connectionHealth));
                case "wait" -> sections.add(runCheck("等待事件健康", this::waitHealth));
                case "storage" -> sections.add(runCheck("存储健康", this::storageHealth));
                case "sequence" -> sections.add(runCheck("序列健康", this::sequenceHealth));
                case "buffer" -> sections.add(runCheck("缓冲区和内存健康", this::bufferHealth));
                case "constraint" -> sections.add(runCheck("约束健康", this::constraintHealth));
                default -> throw new IllegalArgumentException("不支持的达梦健康检查类型：" + type);
            }
        }
        return String.join("\n\n", sections);
    }

    @Override
    public String analyzeWorkloadIndexes(int maxIndexSizeMb, String method) {
        if (isLlmMethod(method)) {
            return llmDeferredMessage();
        }
        try {
            QueryResult result = sqlClient.query("""
                SELECT *
                FROM (
                    SELECT
                        COALESCE(TOP_SQL_TEXT, SEC_SQL_TEXT, THRD_SQL_TEXT) AS query,
                        COUNT(*) AS executions,
                        SUM(TIME_USED) AS total_elapsed_time
                    FROM V$SQL_HISTORY
                    WHERE COALESCE(TOP_SQL_TEXT, SEC_SQL_TEXT, THRD_SQL_TEXT) IS NOT NULL
                    GROUP BY COALESCE(TOP_SQL_TEXT, SEC_SQL_TEXT, THRD_SQL_TEXT)
                    ORDER BY total_elapsed_time DESC
                )
                WHERE ROWNUM <= 10
                """);
            return "达梦工作负载索引建议（method=rule_engine, max_index_size_mb=" + maxIndexSizeMb + "）\n"
                + renderRows(result)
                + "\n建议依据：检查高成本 SELECT 的过滤列和连接列，并在人工创建索引前对照 ALL_INDEXES/ALL_IND_COLUMNS。";
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
        if (isLlmMethod(method)) {
            return llmDeferredMessage();
        }
        StringBuilder builder = new StringBuilder(
            "达梦查询索引建议（method=rule_engine, max_index_size_mb=" + maxIndexSizeMb + "）"
        );
        for (String query : queries) {
            builder.append("\n\n查询：").append(query);
            try {
                builder.append("\n执行计划：\n").append(renderRows(sqlClient.query("EXPLAIN " + query)));
            } catch (RuntimeException e) {
                builder.append("\n执行计划不可用：").append(SecretMasker.mask(e.getMessage()));
            }
            builder.append("\n建议依据：检查过滤列、连接列和已有 ALL_IND_COLUMNS 记录，再人工评估是否创建索引。");
        }
        return builder.toString();
    }

    private String indexHealth() {
        QueryResult result = sqlClient.query("""
            SELECT OWNER, INDEX_NAME, TABLE_NAME, STATUS, UNIQUENESS
            FROM ALL_INDEXES
            WHERE STATUS IS NOT NULL AND STATUS <> 'VALID'
            ORDER BY OWNER, TABLE_NAME, INDEX_NAME
            """);
        return renderRowsOrHealthy(result, "当前用户未看到无效索引。");
    }

    private String connectionHealth() {
        QueryResult result = sqlClient.query("""
            SELECT STATE, COUNT(*) AS session_count
            FROM V$SESSIONS
            GROUP BY STATE
            ORDER BY session_count DESC
            """);
        return renderRows(result);
    }

    private String waitHealth() {
        QueryResult result = sqlClient.query("""
            SELECT *
            FROM (
                SELECT EVENT, TOTAL_WAITS, TIME_WAITED
                FROM V$SYSTEM_EVENT
                ORDER BY TIME_WAITED DESC
            )
            WHERE ROWNUM <= 10
            """);
        return renderRows(result);
    }

    private String storageHealth() {
        QueryResult result = sqlClient.query("""
            SELECT OWNER, TABLE_NAME, NUM_ROWS, BLOCKS
            FROM ALL_TABLES
            WHERE OWNER NOT IN ('SYS', 'SYSDBA')
            ORDER BY BLOCKS DESC
            """);
        return renderRows(result);
    }

    private String sequenceHealth() {
        QueryResult result = sqlClient.query("""
            SELECT SEQUENCE_OWNER, SEQUENCE_NAME, MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE_FLAG
            FROM ALL_SEQUENCES
            ORDER BY SEQUENCE_OWNER, SEQUENCE_NAME
            """);
        return renderRowsOrHealthy(result, "当前用户未看到序列。");
    }

    private String bufferHealth() {
        QueryResult result = sqlClient.query("""
            SELECT *
            FROM (
                SELECT NAME, TOTAL_SIZE, DATA_SIZE, RESERVED_SIZE, TARGET_SIZE, PEAK_SIZE
                FROM V$MEM_POOL
                ORDER BY TOTAL_SIZE DESC
            )
            WHERE ROWNUM <= 10
            """);
        return renderRows(result);
    }

    private String constraintHealth() {
        QueryResult result = sqlClient.query("""
            SELECT OWNER, TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE, STATUS
            FROM ALL_CONSTRAINTS
            WHERE STATUS IS NOT NULL AND STATUS <> 'ENABLED'
            ORDER BY OWNER, TABLE_NAME, CONSTRAINT_NAME
            """);
        return renderRowsOrHealthy(result, "当前用户未看到禁用约束。");
    }

    private String runCheck(String title, HealthCheck check) {
        try {
            return "## " + title + "\n" + check.run();
        } catch (RuntimeException e) {
            return "## " + title + "\n" + degraded(title, e);
        }
    }

    private static String renderRowsOrHealthy(QueryResult result, String healthyMessage) {
        return result.rows().isEmpty() ? healthyMessage : renderRows(result);
    }

    private static String renderRows(QueryResult result) {
        if (result.rows().isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> row : result.rows()) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(row);
        }
        return builder.toString();
    }

    private static String degraded(String section, RuntimeException e) {
        return "当前权限或版本无法获取达梦" + section + "："
            + SecretMasker.mask(e.getMessage());
    }

    private static String normalizeTopQuerySort(String sortBy) {
        String normalized = sortBy == null || sortBy.isBlank() ? "total_time" : sortBy.toLowerCase(Locale.ROOT);
        return TOP_QUERY_SORT_BY.contains(normalized) ? normalized : "total_time";
    }

    private static List<String> normalizeHealthTypes(String healthType) {
        String requested = healthType == null || healthType.isBlank() ? "all" : healthType.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (String type : requested.split(",")) {
            String trimmed = type.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("all".equals(trimmed)) {
                types.addAll(List.of("index", "connection", "wait", "storage", "sequence", "buffer", "constraint"));
            } else if (HEALTH_TYPES.contains(trimmed)) {
                types.add(trimmed);
            } else {
                throw new IllegalArgumentException("不支持的达梦健康检查类型：" + trimmed);
            }
        }
        return new ArrayList<>(types);
    }

    private static boolean isLlmMethod(String method) {
        return "llm".equalsIgnoreCase(method == null || method.isBlank() ? "dta" : method);
    }

    private static String llmDeferredMessage() {
        return "LLM 索引优化方法保留为后续阶段接入；当前达梦版本支持 method='dta' 的规则建议。";
    }

    @FunctionalInterface
    private interface HealthCheck {
        String run();
    }
}
