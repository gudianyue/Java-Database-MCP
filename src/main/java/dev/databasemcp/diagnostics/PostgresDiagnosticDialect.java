package dev.databasemcp.diagnostics;

import static dev.databasemcp.diagnostics.DiagnosticSupport.firstValue;
import static dev.databasemcp.diagnostics.DiagnosticSupport.isSelect;
import static dev.databasemcp.diagnostics.DiagnosticSupport.joinRows;
import static dev.databasemcp.diagnostics.DiagnosticSupport.megabytes;
import static dev.databasemcp.diagnostics.DiagnosticSupport.number;
import static dev.databasemcp.diagnostics.DiagnosticSupport.qualified;
import static dev.databasemcp.diagnostics.DiagnosticSupport.round;
import static dev.databasemcp.diagnostics.DiagnosticSupport.singleLong;
import static dev.databasemcp.diagnostics.DiagnosticSupport.truthy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SecretMasker;
import dev.databasemcp.sql.SqlClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 诊断方言，集中处理慢查询、健康检查和索引建议。
 */
@Component
public class PostgresDiagnosticDialect implements DiagnosticDialect {

    private static final Set<String> PG_HEALTH_TYPES = Collections.unmodifiableSet(
        new LinkedHashSet<>(List.of("index", "connection", "vacuum", "sequence", "replication", "buffer", "constraint", "all"))
    );
    private static final String PG_STAT_STATEMENTS = "pg_stat_statements";
    private static final String HYPOPG = "hypopg";
    private static final int MAX_NUM_INDEX_TUNING_QUERIES = 10;

    private final SqlClient sqlClient;
    private final PostgresExtensionService extensionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostgresDiagnosticDialect(SqlClient sqlClient, PostgresExtensionService extensionService) {
        this.sqlClient = sqlClient;
        this.extensionService = extensionService;
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.POSTGRESQL;
    }

    // ==================== 慢查询统计 ====================

    @Override
    public String getTopQueries(String sortBy, int limit) {
        String criteria = sortBy == null || sortBy.isBlank() ? "resources" : sortBy;
        if (!extensionService.isExtensionInstalled(PG_STAT_STATEMENTS)) {
            return pgStatStatementsInstallMessage();
        }
        return switch (criteria) {
            case "resources" -> sqlClient.query(resourceQuery(columns())).rows().toString();
            case "mean_time" -> topQueriesByTime(limit, "mean");
            case "total_time" -> topQueriesByTime(limit, "total");
            default -> throw new IllegalArgumentException(
                "无效排序条件。请使用 'resources'、'mean_time' 或 'total_time'。"
            );
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
    ) {}

    // ==================== 健康检查 ====================

    @Override
    public String analyzeHealth(String healthType) {
        LinkedHashSet<String> healthTypes = parseHealthTypes(healthType);
        if (healthTypes.contains("all")) {
            healthTypes = new LinkedHashSet<>(List.of(
                "index", "connection", "vacuum", "sequence", "replication", "buffer", "constraint"
            ));
        }

        List<String> sections = new ArrayList<>();
        for (String type : healthTypes) {
            switch (type) {
                case "index" -> sections.add(runCheck("索引健康", this::indexHealth));
                case "connection" -> sections.add(runCheck("连接健康", this::connectionHealth));
                case "vacuum" -> sections.add(runCheck("Vacuum 健康", this::vacuumHealth));
                case "sequence" -> sections.add(runCheck("序列健康", this::sequenceHealth));
                case "replication" -> sections.add(runCheck("复制健康", this::replicationHealth));
                case "buffer" -> sections.add(runCheck("缓冲区健康", this::bufferHealth));
                case "constraint" -> sections.add(runCheck("约束健康", this::constraintHealth));
                default -> throw new IllegalArgumentException("不支持的健康检查类型：" + type);
            }
        }
        return sections.isEmpty() ? "未执行任何健康检查。" : String.join(System.lineSeparator(), sections);
    }

    private static LinkedHashSet<String> parseHealthTypes(String healthType) {
        String raw = healthType == null || healthType.isBlank() ? "all" : healthType;
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        java.util.Arrays.stream(raw.split(","))
            .map(String::trim)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .forEach(parsed::add);
        for (String type : parsed) {
            if (!PG_HEALTH_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                    "无效健康检查类型：'" + healthType + "'。有效值为：" + String.join(", ", PG_HEALTH_TYPES)
                );
            }
        }
        return parsed;
    }

    private String indexHealth() {
        return String.join(System.lineSeparator(),
            "无效索引检查：" + invalidIndexes(),
            "重复索引检查：" + duplicateIndexes(),
            "索引膨胀检查：" + largeIndexes(),
            "低使用索引检查：" + unusedIndexes()
        );
    }

    private String invalidIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                n.nspname AS schema,
                ix.relname AS index,
                t.relname AS table
            FROM pg_index i
            JOIN pg_class ix ON ix.oid = i.indexrelid
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = ix.relnamespace
            WHERE NOT i.indisvalid
            ORDER BY 1, 2
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现无效索引。";
        }
        return joinRows(result.rows(), row -> "索引 " + qualified(row, "schema", "index") + " 在表 " + row.get("table") + " 上无效。");
    }

    private String duplicateIndexes() {
        QueryResult result = sqlClient.query(
            """
            WITH index_defs AS (
                SELECT
                    n.nspname AS schema,
                    t.relname AS table,
                    ix.relname AS index,
                    regexp_replace(pg_get_indexdef(i.indexrelid), '^CREATE (UNIQUE )?INDEX [^ ]+ ON ', 'CREATE INDEX ON ') AS normalized_definition,
                    i.indisprimary,
                    i.indisunique
                FROM pg_index i
                JOIN pg_class ix ON ix.oid = i.indexrelid
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = ix.relnamespace
                WHERE i.indisvalid
            )
            SELECT
                a.schema,
                a.table,
                a.index AS duplicate_index,
                b.index AS covering_index
            FROM index_defs a
            JOIN index_defs b
                ON a.schema = b.schema
                AND a.table = b.table
                AND a.normalized_definition = b.normalized_definition
                AND a.index > b.index
            WHERE NOT a.indisprimary
                AND NOT a.indisunique
            ORDER BY 1, 2, 3
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现重复索引。";
        }
        return joinRows(result.rows(), row ->
            "索引 " + row.get("duplicate_index") + " 与 " + row.get("covering_index") + " 在表 " + row.get("table") + " 上重复。"
        );
    }

    private String largeIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                schemaname AS schema,
                relname AS table,
                indexrelname AS index,
                pg_relation_size(indexrelid) AS size_bytes
            FROM pg_stat_user_indexes
            WHERE pg_relation_size(indexrelid) >= ?
            ORDER BY pg_relation_size(indexrelid) DESC
            """,
            List.of(104_857_600)
        );
        if (result.rows().isEmpty()) {
            return "未发现超过 100MB 的大索引。";
        }
        return joinRows(result.rows(), row ->
            "索引 " + row.get("index") + " 位于表 " + row.get("table") + "，大小约 " + megabytes(row.get("size_bytes")) + "MB。"
        );
    }

    private String unusedIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                schemaname AS schema,
                relname AS table,
                indexrelname AS index,
                idx_scan AS index_scans,
                pg_relation_size(i.indexrelid) AS size_bytes,
                indisprimary AS primary
            FROM pg_stat_user_indexes ui
            JOIN pg_index i ON ui.indexrelid = i.indexrelid
            WHERE NOT indisunique
                AND idx_scan <= ?
            ORDER BY pg_relation_size(i.indexrelid) DESC, relname ASC
            """,
            List.of(50)
        );
        List<Map<String, Object>> rows = result.rows().stream()
            .filter(row -> !truthy(row.get("primary")))
            .toList();
        if (rows.isEmpty()) {
            return "未发现低使用索引。";
        }
        return joinRows(rows, row ->
            "索引 " + row.get("index") + " 位于表 " + row.get("table") + "，扫描次数 "
                + row.get("index_scans") + "，大小约 " + megabytes(row.get("size_bytes")) + "MB。"
        );
    }

    private String connectionHealth() {
        long total = singleLong(sqlClient.query("SELECT COUNT(*) AS count FROM pg_stat_activity"), "count");
        long idleInTransaction = singleLong(
            sqlClient.query("SELECT COUNT(*) AS count FROM pg_stat_activity WHERE state = 'idle in transaction'"),
            "count"
        );
        if (total > 500) {
            return "连接数过高：" + total + "。";
        }
        if (idleInTransaction > 100) {
            return "idle in transaction 连接数过高：" + idleInTransaction + "。";
        }
        return "连接健康：" + total + " 个总连接，" + idleInTransaction + " 个 idle in transaction。";
    }

    private String vacuumHealth() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                n.nspname AS schema,
                c.relname AS table,
                2146483648 - GREATEST(AGE(c.relfrozenxid), AGE(t.relfrozenxid)) AS transactions_left
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_class t ON c.reltoastrelid = t.oid
            WHERE c.relkind = 'r'
                AND (2146483648 - GREATEST(AGE(c.relfrozenxid), AGE(t.relfrozenxid))) < ?
            ORDER BY 3, 1, 2
            """,
            List.of(10_000_000)
        );
        if (result.rows().isEmpty()) {
            return "未发现接近事务 ID 回卷风险的表。";
        }
        return joinRows(result.rows(), row ->
            "表 " + qualified(row, "schema", "table") + " 距离事务 ID 回卷约剩余 " + row.get("transactions_left") + " 个事务。"
        );
    }

    private String sequenceHealth() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                sequence_schema AS schema,
                sequence_name AS sequence,
                data_type,
                start_value,
                minimum_value,
                maximum_value
            FROM information_schema.sequences
            WHERE sequence_schema NOT IN ('pg_catalog', 'information_schema')
            ORDER BY 1, 2
            """
        );
        if (result.rows().isEmpty()) {
            return "数据库中未发现序列。";
        }
        return "发现 " + result.rows().size() + " 个序列；请结合业务增长速度检查接近最大值的序列。";
    }

    private String replicationHealth() {
        boolean replica = truthy(firstValue(sqlClient.query("SELECT pg_is_in_recovery() AS replica"), "replica"));
        long activeReplicas = singleLong(sqlClient.query("SELECT COUNT(*) AS count FROM pg_stat_replication"), "count");
        QueryResult slots = sqlClient.query(
            """
            SELECT
                slot_name,
                database,
                active
            FROM pg_replication_slots
            ORDER BY slot_name
            """
        );
        List<String> lines = new ArrayList<>();
        if (replica) {
            lines.add("当前数据库是副本。");
            lines.add(replicationLag());
        } else {
            lines.add("当前数据库是主库。");
            lines.add(activeReplicas > 0 ? "存在活跃副本连接：" + activeReplicas + "。" : "未发现活跃副本连接。");
        }
        if (slots.rows().isEmpty()) {
            lines.add("未发现复制槽。");
        } else {
            lines.add("复制槽数量：" + slots.rows().size() + "。");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String replicationLag() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                CASE
                    WHEN NOT pg_is_in_recovery()
                        OR pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn()
                    THEN 0
                    ELSE EXTRACT(EPOCH FROM NOW() - pg_last_xact_replay_timestamp())
                END AS replication_lag
            """
        );
        Object lag = firstValue(result, "replication_lag");
        if (lag == null) {
            return "无法读取复制延迟。";
        }
        return "复制延迟：" + lag + " 秒。";
    }

    private String bufferHealth() {
        String indexRate = cacheHitRate(
            sqlClient.query(
                """
                SELECT
                    sum(idx_blks_hit)::numeric / nullif(sum(idx_blks_hit + idx_blks_read), 0) AS rate
                FROM pg_statio_user_indexes
                """
            ),
            "索引缓存"
        );
        String tableRate = cacheHitRate(
            sqlClient.query(
                """
                SELECT
                    sum(heap_blks_hit)::numeric / nullif(sum(heap_blks_hit + heap_blks_read), 0) AS rate
                FROM pg_statio_user_tables
                """
            ),
            "表缓存"
        );
        return indexRate + System.lineSeparator() + tableRate;
    }

    private String cacheHitRate(QueryResult result, String label) {
        Object value = firstValue(result, "rate");
        if (value == null) {
            return label + "暂无统计数据。";
        }
        BigDecimal percent = number(value).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        String relation = percent.compareTo(BigDecimal.valueOf(95)) >= 0 ? "高于" : "低于";
        return label + "命中率：" + percent + "%，" + relation + " 95.0% 阈值。";
    }

    private String constraintHealth() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                nsp.nspname AS schema,
                rel.relname AS table,
                con.conname AS name,
                fnsp.nspname AS referenced_schema,
                frel.relname AS referenced_table
            FROM pg_constraint con
            JOIN pg_class rel ON rel.oid = con.conrelid
            LEFT JOIN pg_class frel ON frel.oid = con.confrelid
            LEFT JOIN pg_namespace nsp ON nsp.oid = con.connamespace
            LEFT JOIN pg_namespace fnsp ON fnsp.oid = frel.relnamespace
            WHERE con.convalidated = false
            ORDER BY 1, 2, 3
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现无效约束。";
        }
        return joinRows(result.rows(), row ->
            "约束 " + row.get("name") + " 位于表 " + qualified(row, "schema", "table") + "，当前未验证。"
        );
    }

    // ==================== 索引建议 ====================

    @Override
    public String analyzeWorkloadIndexes(int maxIndexSizeMb, String method) {
        if (!extensionService.isExtensionInstalled(PG_STAT_STATEMENTS)) {
            return pgStatStatementsInstallMessage();
        }
        String totalTimeColumn = extensionService.postgresMajorVersion() >= 13 ? "total_exec_time" : "total_time";
        QueryResult result = sqlClient.query(
            """
            SELECT
                query,
                calls,
                %s / NULLIF(calls, 0) AS avg_exec_time
            FROM pg_stat_statements
            WHERE calls >= ?
                AND %s / NULLIF(calls, 0) >= ?
            ORDER BY %s DESC
            LIMIT ?
            """.formatted(totalTimeColumn, totalTimeColumn, totalTimeColumn),
            List.of(50, 5.0, MAX_NUM_INDEX_TUNING_QUERIES)
        );
        List<WorkloadQuery> workload = result.rows().stream()
            .map(row -> new WorkloadQuery(
                String.valueOf(row.get("query")),
                number(row.getOrDefault("calls", 1)).doubleValue() * number(row.getOrDefault("avg_exec_time", 1)).doubleValue()
            ))
            .toList();
        return analyze(workload, maxIndexSizeMb, "query_store");
    }

    @Override
    public String analyzeQueryIndexes(List<String> queries, int maxIndexSizeMb, String method) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("请提供非空 SQL 查询列表。");
        }
        if (queries.size() > MAX_NUM_INDEX_TUNING_QUERIES) {
            throw new IllegalArgumentException("请提供最多 " + MAX_NUM_INDEX_TUNING_QUERIES + " 条 SQL 查询。");
        }
        List<WorkloadQuery> workload = queries.stream()
            .filter(query -> query != null && !query.isBlank())
            .map(query -> new WorkloadQuery(query, 1.0))
            .toList();
        return analyze(workload, maxIndexSizeMb, "query_list");
    }

    private String analyze(List<WorkloadQuery> workload, int maxIndexSizeMb, String source) {
        if (workload.isEmpty()) {
            return "未找到可分析的查询。";
        }
        if (!extensionService.isExtensionInstalled(HYPOPG)) {
            return hypopgInstallMessage();
        }
        if (!hasAnalyzeStats()) {
            return "数据库统计信息不足。请先在数据库中执行 ANALYZE;，否则索引建议可能不准确。";
        }

        List<PlannedQuery> plannedQueries = workload.stream()
            .map(this::explain)
            .filter(Objects::nonNull)
            .toList();
        if (plannedQueries.isEmpty()) {
            return "未找到可分析的 SELECT 查询。";
        }

        List<IndexCandidate> candidates = generateCandidates(plannedQueries);
        if (candidates.isEmpty()) {
            return "未发现可推荐的新索引。";
        }

        List<IndexCandidate> evaluated = evaluateCandidates(plannedQueries, candidates, maxIndexSizeMb);
        if (evaluated.isEmpty()) {
            return "未发现能降低执行计划成本且符合大小预算的索引。";
        }
        return render(source, plannedQueries, evaluated);
    }

    private PlannedQuery explain(WorkloadQuery workloadQuery) {
        if (!isSelect(workloadQuery.sql())) {
            return null;
        }
        QueryResult result = sqlClient.query("EXPLAIN (FORMAT JSON) " + workloadQuery.sql());
        Object rawPlan = result.rows().isEmpty() ? null : result.rows().getFirst().values().stream().findFirst().orElse(null);
        JsonNode root = parsePlan(rawPlan);
        JsonNode plan = root.isArray() ? root.get(0).get("Plan") : root.get("Plan");
        if (plan == null || plan.isMissingNode()) {
            return null;
        }
        double cost = plan.path("Total Cost").asDouble(Double.POSITIVE_INFINITY);
        List<PlanRelation> relations = collectRelations(plan);
        return new PlannedQuery(workloadQuery.sql(), workloadQuery.weight(), cost, relations);
    }

    private List<PlanRelation> collectRelations(JsonNode plan) {
        List<PlanRelation> relations = new ArrayList<>();
        ArrayDeque<JsonNode> queue = new ArrayDeque<>();
        queue.add(plan);
        while (!queue.isEmpty()) {
            JsonNode node = queue.removeFirst();
            if (node.has("Relation Name")) {
                String relation = node.path("Relation Name").asText();
                String schema = node.path("Schema").asText("public");
                String conditionText = String.join(" ",
                    node.path("Filter").asText(""),
                    node.path("Index Cond").asText(""),
                    node.path("Join Filter").asText(""),
                    node.path("Recheck Cond").asText("")
                );
                boolean sequential = node.path("Node Type").asText("").toLowerCase(Locale.ROOT).contains("seq scan");
                relations.add(new PlanRelation(schema, relation, conditionText, sequential));
            }
            JsonNode childPlans = node.path("Plans");
            if (childPlans.isArray()) {
                childPlans.forEach(queue::addLast);
            }
        }
        return relations;
    }

    private List<IndexCandidate> generateCandidates(List<PlannedQuery> plannedQueries) {
        Map<TableRef, Set<String>> existingIndexes = existingSimpleIndexes();
        Map<TableRef, Set<String>> candidateColumns = new LinkedHashMap<>();
        for (PlannedQuery query : plannedQueries) {
            for (PlanRelation relation : query.relations()) {
                if (!relation.sequential() && relation.conditionText().isBlank()) {
                    continue;
                }
                TableRef table = new TableRef(relation.schema(), relation.table());
                Set<String> columns = tableColumns(table);
                for (String column : columns) {
                    Pattern columnPattern = Pattern.compile("(?i)(^|[^A-Za-z0-9_])" + Pattern.quote(column) + "([^A-Za-z0-9_]|$)");
                    if (columnPattern.matcher(relation.conditionText()).find()) {
                        candidateColumns.computeIfAbsent(table, ignored -> new LinkedHashSet<>()).add(column);
                    }
                }
            }
        }

        List<IndexCandidate> candidates = new ArrayList<>();
        candidateColumns.forEach((table, columns) -> {
            for (String column : columns) {
                if (!existingIndexes.getOrDefault(table, Set.of()).contains(column)) {
                    long size = estimateIndexSize(table, List.of(column));
                    candidates.add(new IndexCandidate(table, List.of(column), "btree", definition(table, List.of(column), "btree"), size, 0, 0, 0));
                }
            }
        });
        return candidates;
    }

    private List<IndexCandidate> evaluateCandidates(List<PlannedQuery> plannedQueries, List<IndexCandidate> candidates, int maxIndexSizeMb) {
        long budgetBytes = maxIndexSizeMb <= 0 ? Long.MAX_VALUE : maxIndexSizeMb * 1024L * 1024L;
        List<IndexCandidate> evaluated = new ArrayList<>();
        for (IndexCandidate candidate : candidates) {
            if (candidate.estimatedSizeBytes() > budgetBytes) {
                continue;
            }
            try {
                sqlClient.query("SELECT hypopg_reset()");
                sqlClient.query("SELECT * FROM hypopg_create_index(?)", List.of(candidate.definition()));
                double baseCost = weightedCost(plannedQueries);
                double newCost = plannedQueries.stream()
                    .mapToDouble(query -> {
                        PlannedQuery planned = explain(new WorkloadQuery(query.sql(), query.weight()));
                        return planned == null ? query.cost() * query.weight() : planned.cost() * query.weight();
                    })
                    .sum();
                double improvement = improvementMultiple(baseCost, newCost);
                if (newCost < baseCost) {
                    evaluated.add(candidate.withCosts(baseCost, newCost, improvement));
                }
            } finally {
                sqlClient.query("SELECT hypopg_reset()");
            }
        }
        return evaluated.stream()
            .sorted(Comparator.comparingDouble(IndexCandidate::improvementMultiple).reversed())
            .limit(MAX_NUM_INDEX_TUNING_QUERIES)
            .toList();
    }

    private double weightedCost(List<PlannedQuery> plannedQueries) {
        return plannedQueries.stream().mapToDouble(query -> query.cost() * query.weight()).sum();
    }

    private String render(String source, List<PlannedQuery> plannedQueries, List<IndexCandidate> recommendations) {
        double baseCost = weightedCost(plannedQueries);
        double newCost = recommendations.stream().findFirst().map(IndexCandidate::newCost).orElse(baseCost);
        long totalSize = recommendations.stream().mapToLong(IndexCandidate::estimatedSizeBytes).sum();
        List<String> lines = new ArrayList<>();
        lines.add("{summary={workload_source=" + source
            + ", total_recommendations=" + recommendations.size()
            + ", base_cost=" + round(baseCost)
            + ", best_new_cost=" + round(newCost)
            + ", total_size_bytes=" + totalSize
            + "}, recommendations=[");
        for (int i = 0; i < recommendations.size(); i++) {
            IndexCandidate candidate = recommendations.get(i);
            lines.add("  {index_apply_order=" + (i + 1)
                + ", index_target_table=" + candidate.table().qualifiedName()
                + ", index_target_columns=" + candidate.columns()
                + ", benefit_of_this_index_only={improvement_multiple=" + round(candidate.improvementMultiple())
                + ", base_cost=" + round(candidate.baseCost())
                + ", new_cost=" + round(candidate.newCost())
                + "}, index_estimated_size_bytes=" + candidate.estimatedSizeBytes()
                + ", index_definition=" + candidate.definition()
                + "}");
        }
        lines.add("]}");
        return String.join(System.lineSeparator(), lines);
    }

    private Map<TableRef, Set<String>> existingSimpleIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                n.nspname AS schema,
                t.relname AS table,
                a.attname AS column
            FROM pg_index i
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN unnest(i.indkey) WITH ORDINALITY AS keys(attnum, ordinality) ON true
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = keys.attnum
            WHERE i.indexprs IS NULL
                AND n.nspname NOT IN ('pg_catalog', 'information_schema')
            ORDER BY 1, 2, keys.ordinality
            """
        );
        Map<TableRef, Set<String>> indexes = new LinkedHashMap<>();
        for (Map<String, Object> row : result.rows()) {
            TableRef table = new TableRef(String.valueOf(row.get("schema")), String.valueOf(row.get("table")));
            indexes.computeIfAbsent(table, ignored -> new HashSet<>()).add(String.valueOf(row.get("column")));
        }
        return indexes;
    }

    private Set<String> tableColumns(TableRef table) {
        QueryResult result = sqlClient.query(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = ?
                AND table_name = ?
                AND data_type NOT IN ('text', 'bytea', 'json', 'jsonb')
                AND (character_maximum_length IS NULL OR character_maximum_length <= 255)
            ORDER BY ordinal_position
            """,
            List.of(table.schema(), table.name())
        );
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : result.rows()) {
            columns.add(String.valueOf(row.get("column_name")));
        }
        return columns;
    }

    private long estimateIndexSize(TableRef table, List<String> columns) {
        QueryResult result = sqlClient.query(
            """
            SELECT
                COALESCE(c.reltuples, 1000) AS row_estimate,
                COALESCE(SUM(s.avg_width), 16) AS total_width
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_stats s
                ON s.schemaname = n.nspname
                AND s.tablename = c.relname
                AND s.attname = ANY(?)
            WHERE n.nspname = ?
                AND c.relname = ?
            GROUP BY c.reltuples
            """,
            List.of(columns.toArray(String[]::new), table.schema(), table.name())
        );
        if (result.rows().isEmpty()) {
            return 0;
        }
        Map<String, Object> row = result.rows().getFirst();
        BigDecimal rows = number(row.get("row_estimate")).max(BigDecimal.ONE);
        BigDecimal width = number(row.get("total_width")).add(BigDecimal.valueOf(8));
        return rows.multiply(width).multiply(BigDecimal.valueOf(1.2)).longValue();
    }

    private JsonNode parsePlan(Object rawPlan) {
        try {
            if (rawPlan instanceof JsonNode node) {
                return node;
            }
            return objectMapper.readTree(String.valueOf(rawPlan));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法解析 PostgreSQL EXPLAIN JSON 计划", e);
        }
    }

    private boolean hasAnalyzeStats() {
        QueryResult result = sqlClient.query(
            "SELECT s.last_analyze FROM pg_stat_user_tables s ORDER BY s.last_analyze DESC NULLS LAST LIMIT 1"
        );
        return result.rows().isEmpty() || result.rows().stream().anyMatch(row -> row.get("last_analyze") != null);
    }

    // ==================== 通用辅助 ====================

    private String runCheck(String title, Supplier<String> check) {
        try {
            return title + "：" + System.lineSeparator() + check.get();
        } catch (Exception e) {
            return title + "：" + System.lineSeparator() + "检查失败：" + SecretMasker.mask(e.getMessage());
        }
    }

    private static String definition(TableRef table, List<String> columns, String using) {
        String name = "crystaldba_idx_" + table.name() + "_" + String.join("_", columns);
        String renderedColumns = columns.stream().map(PostgresDiagnosticDialect::quoteIdentifier).collect(Collectors.joining(", "));
        return "CREATE INDEX " + quoteIdentifier(name) + " ON " + table.qualifiedQuotedName() + " USING " + using + " (" + renderedColumns + ")";
    }

    private static String quoteIdentifier(String value) {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法标识符：" + value);
        }
        return "\"" + value + "\"";
    }

    private static double improvementMultiple(double baseCost, double newCost) {
        if (baseCost <= 0 || newCost <= 0) {
            return 0;
        }
        return baseCost / newCost;
    }

    private static String hypopgInstallMessage() {
        return """
            hypopg 扩展是 DTA 索引调优评估所必需的，但当前数据库尚未安装。

            可以在数据库中执行：CREATE EXTENSION hypopg;

            Java 版本会用 HypoPG 创建会话级假设索引并比较 EXPLAIN 成本，不会真正创建物理索引。
            """.strip();
    }

    private record WorkloadQuery(String sql, double weight) {}
    private record PlannedQuery(String sql, double weight, double cost, List<PlanRelation> relations) {}
    private record PlanRelation(String schema, String table, String conditionText, boolean sequential) {}
    private record TableRef(String schema, String name) {
        private String qualifiedName() { return schema + "." + name; }
        private String qualifiedQuotedName() { return quoteIdentifier(schema) + "." + quoteIdentifier(name); }
    }
    private record IndexCandidate(
        TableRef table, List<String> columns, String using, String definition,
        long estimatedSizeBytes, double baseCost, double newCost, double improvementMultiple
    ) {
        private IndexCandidate withCosts(double nextBaseCost, double nextNewCost, double nextImprovementMultiple) {
            return new IndexCandidate(table, columns, using, definition, estimatedSizeBytes, nextBaseCost, nextNewCost, nextImprovementMultiple);
        }
    }
}
