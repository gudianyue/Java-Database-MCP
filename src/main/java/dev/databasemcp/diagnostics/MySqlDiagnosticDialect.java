package dev.databasemcp.diagnostics;

import static dev.databasemcp.diagnostics.DiagnosticSupport.firstValue;
import static dev.databasemcp.diagnostics.DiagnosticSupport.isSelect;
import static dev.databasemcp.diagnostics.DiagnosticSupport.joinRows;
import static dev.databasemcp.diagnostics.DiagnosticSupport.megabytes;
import static dev.databasemcp.diagnostics.DiagnosticSupport.number;
import static dev.databasemcp.diagnostics.DiagnosticSupport.qualified;
import static dev.databasemcp.diagnostics.DiagnosticSupport.round;
import static dev.databasemcp.diagnostics.DiagnosticSupport.singleLong;
import static dev.databasemcp.diagnostics.DiagnosticSupport.singleLongFromRow;
import static dev.databasemcp.diagnostics.DiagnosticSupport.truthy;
import static dev.databasemcp.diagnostics.DiagnosticSupport.usesLlm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SecretMasker;
import dev.databasemcp.sql.SqlClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * MySQL 诊断方言，使用 performance_schema、information_schema 和
 * InnoDB 状态变量实现慢查询统计、健康检查和索引建议。
 */
@Component
public class MySqlDiagnosticDialect implements DiagnosticDialect {

    private static final long MYSQL_PERFORMANCE_SCHEMA_TIMER_DIVISOR = 1_000_000_000_000L;
    private static final Set<String> MYSQL_HEALTH_TYPES = Collections.unmodifiableSet(
        new LinkedHashSet<>(List.of(
            "index", "connection", "fragmentation", "auto_increment", "replication", "buffer", "constraint", "all"
        ))
    );
    private static final int MAX_NUM_INDEX_TUNING_QUERIES = 10;

    private final SqlClient sqlClient;
    private final MySqlFeatureService featureService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MySqlDiagnosticDialect(SqlClient sqlClient, MySqlFeatureService featureService) {
        this.sqlClient = sqlClient;
        this.featureService = featureService;
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.MYSQL;
    }

    // ==================== 慢查询统计 ====================

    @Override
    public String getTopQueries(String sortBy, int limit) {
        if (!featureService.isPerformanceSchemaEnabled()) {
            return featureService.performanceSchemaInstallMessage();
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        String criteria = sortBy == null || sortBy.isBlank() ? "total_time" : sortBy;
        return switch (criteria) {
            case "mean_time" -> topQueriesByTime(limit, "mean");
            case "total_time" -> topQueriesByTime(limit, "total");
            default -> throw new IllegalArgumentException(
                "无效排序条件。MySQL 支持 'mean_time' 或 'total_time'。"
            );
        };
    }

    private String topQueriesByTime(int limit, String sortBy) {
        String orderBy = "total".equals(sortBy)
            ? "SUM_TIMER_WAIT"
            : "SUM_TIMER_WAIT / NULLIF(COUNT_STAR, 0)";
        String criteriaLabel = "total".equals(sortBy) ? "总执行时间" : "单次平均执行时间";
        QueryResult result = sqlClient.query(
            """
            SELECT
                DIGEST_TEXT AS query,
                COUNT_STAR AS calls,
                SUM_TIMER_WAIT / %d AS total_exec_time_sec,
                SUM_TIMER_WAIT / NULLIF(COUNT_STAR, 0) / %d AS mean_exec_time_sec,
                SUM_ROWS_EXAMINED AS rows_examined,
                SUM_ROWS_SENT AS rows_sent,
                SUM_ERRORS AS errors
            FROM performance_schema.events_statements_summary_by_digest
            WHERE DIGEST_TEXT IS NOT NULL
            ORDER BY %s DESC
            LIMIT ?
            """.formatted(MYSQL_PERFORMANCE_SCHEMA_TIMER_DIVISOR, MYSQL_PERFORMANCE_SCHEMA_TIMER_DIVISOR, orderBy),
            List.of(limit)
        );
        List<Map<String, Object>> rows = result.rows();
        return "按" + criteriaLabel + "排序的前 " + rows.size() + " 条慢查询：\n" + rows;
    }

    // ==================== 健康检查 ====================

    @Override
    public String analyzeHealth(String healthType) {
        LinkedHashSet<String> healthTypes = parseHealthTypes(healthType);
        if (healthTypes.contains("all")) {
            healthTypes = new LinkedHashSet<>(List.of(
                "index", "connection", "fragmentation", "auto_increment",
                "replication", "buffer", "constraint"
            ));
        }

        List<String> sections = new ArrayList<>();
        for (String type : healthTypes) {
            switch (type) {
                case "index" -> sections.add(runCheck("索引健康", this::indexHealth));
                case "connection" -> sections.add(runCheck("连接健康", this::connectionHealth));
                case "fragmentation" -> sections.add(runCheck("碎片健康", this::fragmentationHealth));
                case "auto_increment" -> sections.add(runCheck("自增列健康", this::autoIncrementHealth));
                case "replication" -> sections.add(runCheck("复制健康", this::replicationHealth));
                case "buffer" -> sections.add(runCheck("缓冲池健康", this::bufferHealth));
                case "constraint" -> sections.add(runCheck("约束健康", this::constraintHealth));
                default -> throw new IllegalArgumentException("不支持的健康检查类型：" + type);
            }
        }
        return sections.isEmpty() ? "未执行任何健康检查。" : String.join(System.lineSeparator(), sections);
    }

    private static LinkedHashSet<String> parseHealthTypes(String healthType) {
        String raw = healthType == null || healthType.isBlank() ? "all" : healthType;
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
            .map(String::trim)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .forEach(parsed::add);
        for (String type : parsed) {
            if (!MYSQL_HEALTH_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                    "无效健康检查类型：'" + healthType + "'。有效值为：" + String.join(", ", MYSQL_HEALTH_TYPES)
                );
            }
        }
        return parsed;
    }

    private String indexHealth() {
        List<String> parts = new ArrayList<>();
        parts.add("重复索引检查：" + duplicateIndexes());
        parts.add("大索引检查：" + largeIndexes());
        if (featureService.isPerformanceSchemaEnabled()) {
            parts.add("低使用索引检查：" + unusedIndexes());
        } else {
            parts.add("低使用索引检查：需要 performance_schema 支持，当前未启用。");
        }
        return String.join(System.lineSeparator(), parts);
    }

    private String duplicateIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                s1.TABLE_SCHEMA AS schema_name,
                s1.TABLE_NAME AS table_name,
                s1.INDEX_NAME AS duplicate_index,
                s2.INDEX_NAME AS covering_index
            FROM information_schema.STATISTICS s1
            JOIN information_schema.STATISTICS s2
                ON s1.TABLE_SCHEMA = s2.TABLE_SCHEMA
                AND s1.TABLE_NAME = s2.TABLE_NAME
                AND s1.COLUMN_NAME = s2.COLUMN_NAME
                AND s1.SEQ_IN_INDEX = s2.SEQ_IN_INDEX
                AND s1.INDEX_NAME > s2.INDEX_NAME
                AND s2.NON_UNIQUE = 0
            WHERE s1.TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            GROUP BY s1.TABLE_SCHEMA, s1.TABLE_NAME, s1.INDEX_NAME, s2.INDEX_NAME
            ORDER BY s1.TABLE_SCHEMA, s1.TABLE_NAME, s1.INDEX_NAME
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现重复索引。";
        }
        return joinRows(result.rows(), row ->
            "索引 " + row.get("duplicate_index") + " 与 " + row.get("covering_index")
                + " 在表 " + qualified(row, "schema_name", "table_name") + " 上重复。"
        );
    }

    private String largeIndexes() {
        long pageSize = innodbPageSize();
        QueryResult result = sqlClient.query(
            """
            SELECT
                DATABASE_NAME AS schema_name,
                TABLE_NAME AS table_name,
                INDEX_NAME AS index_name,
                STAT_VALUE AS size_pages
            FROM mysql.innodb_index_stats
            WHERE STAT_NAME = 'size'
                AND INDEX_NAME != 'PRIMARY'
                AND DATABASE_NAME NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            """
        );
        List<Map<String, Object>> largeRows = result.rows().stream()
            .filter(row -> {
                long pages = singleLongFromRow(row, "size_pages");
                long bytes = pages * pageSize;
                return bytes >= 100L * 1024L * 1024L;
            })
            .sorted(Comparator.comparingLong((Map<String, Object> row) ->
                singleLongFromRow(row, "size_pages") * pageSize).reversed()
            )
            .toList();
        if (largeRows.isEmpty()) {
            return "未发现超过 100MB 的大索引。";
        }
        return joinRows(largeRows, row -> {
            long pages = singleLongFromRow(row, "size_pages");
            long bytes = pages * pageSize;
            return "索引 " + row.get("index_name") + " 位于表 " + qualified(row, "schema_name", "table_name")
                + "，大小约 " + megabytes(bytes) + "MB。";
        });
    }

    private String unusedIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                OBJECT_SCHEMA AS schema_name,
                OBJECT_NAME AS table_name,
                INDEX_NAME AS index_name,
                COUNT_READ AS index_reads,
                COUNT_FETCH AS index_fetches
            FROM performance_schema.table_io_waits_summary_by_index_usage
            WHERE INDEX_NAME IS NOT NULL
                AND INDEX_NAME != 'PRIMARY'
                AND OBJECT_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                AND COUNT_READ <= 50
            ORDER BY OBJECT_SCHEMA, OBJECT_NAME, INDEX_NAME
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现低使用索引。";
        }
        return joinRows(result.rows(), row ->
            "索引 " + row.get("index_name") + " 位于表 " + qualified(row, "schema_name", "table_name")
                + "，读取次数 " + row.get("index_reads") + "。"
        );
    }

    private String connectionHealth() {
        long total = singleLong(
            sqlClient.query("SELECT COUNT(*) AS count FROM information_schema.PROCESSLIST"),
            "count"
        );
        long idleLong = singleLong(
            sqlClient.query(
                "SELECT COUNT(*) AS count FROM information_schema.PROCESSLIST WHERE COMMAND = 'Sleep' AND TIME > 60"
            ),
            "count"
        );
        if (total > 500) {
            return "连接数过高：" + total + "。";
        }
        if (idleLong > 100) {
            return "长时间 Sleep 连接数过高：" + idleLong + "（超过 60 秒）。";
        }
        return "连接健康：" + total + " 个总连接，" + idleLong + " 个长时间 Sleep 连接。";
    }

    private String fragmentationHealth() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                TABLE_SCHEMA AS schema_name,
                TABLE_NAME AS table_name,
                DATA_FREE / 1024 / 1024 AS data_free_mb,
                DATA_LENGTH / 1024 / 1024 AS data_length_mb,
                ROUND(DATA_FREE / NULLIF(DATA_LENGTH + DATA_FREE, 0) * 100, 1) AS fragmentation_pct
            FROM information_schema.TABLES
            WHERE ENGINE = 'InnoDB'
                AND TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                AND DATA_FREE > 100 * 1024 * 1024
            ORDER BY DATA_FREE DESC
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现超过 100MB 空闲空间的碎片化表。";
        }
        return joinRows(result.rows(), row ->
            "表 " + qualified(row, "schema_name", "table_name")
                + "，空闲空间约 " + row.get("data_free_mb") + "MB"
                + "，碎片率 " + row.get("fragmentation_pct") + "%。"
                + "建议执行 OPTIMIZE TABLE " + qualified(row, "schema_name", "table_name") + ";"
        );
    }

    private String autoIncrementHealth() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                t.TABLE_SCHEMA AS schema_name,
                t.TABLE_NAME AS table_name,
                c.COLUMN_NAME AS column_name,
                t.AUTO_INCREMENT AS current_value,
                c.COLUMN_TYPE AS column_type,
                CASE
                    WHEN c.COLUMN_TYPE LIKE '%%tinyint unsigned%%' THEN '255'
                    WHEN c.COLUMN_TYPE LIKE '%%tinyint%%' THEN '127'
                    WHEN c.COLUMN_TYPE LIKE '%%smallint unsigned%%' THEN '65535'
                    WHEN c.COLUMN_TYPE LIKE '%%smallint%%' THEN '32767'
                    WHEN c.COLUMN_TYPE LIKE '%%mediumint unsigned%%' THEN '16777215'
                    WHEN c.COLUMN_TYPE LIKE '%%mediumint%%' THEN '8388607'
                    WHEN c.COLUMN_TYPE LIKE '%%bigint unsigned%%' THEN '18446744073709551615'
                    WHEN c.COLUMN_TYPE LIKE '%%bigint%%' THEN '9223372036854775807'
                    WHEN c.COLUMN_TYPE LIKE '%%int unsigned%%' THEN '4294967295'
                    WHEN c.COLUMN_TYPE LIKE '%%int%%' THEN '2147483647'
                    ELSE 'unknown'
                END AS max_value
            FROM information_schema.TABLES t
            JOIN information_schema.COLUMNS c
                ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
            WHERE c.EXTRA LIKE '%%auto_increment%%'
                AND t.AUTO_INCREMENT IS NOT NULL
                AND t.TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            ORDER BY t.TABLE_SCHEMA, t.TABLE_NAME
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现使用 AUTO_INCREMENT 的表。";
        }
        List<Map<String, Object>> atRisk = result.rows().stream()
            .filter(row -> {
                try {
                    BigDecimal current = number(row.get("current_value"));
                    BigDecimal max = number(row.get("max_value"));
                    BigDecimal remaining = max.subtract(current);
                    BigDecimal threshold = max.multiply(BigDecimal.valueOf(0.1));
                    return remaining.compareTo(threshold) < 0;
                } catch (Exception e) {
                    return false;
                }
            })
            .toList();
        if (atRisk.isEmpty()) {
            return "发现 " + result.rows().size() + " 个 AUTO_INCREMENT 列，均未接近耗尽阈值。";
        }
        return joinRows(atRisk, row ->
            "表 " + qualified(row, "schema_name", "table_name")
                + " 的自增列 " + row.get("column_name")
                + "（类型 " + row.get("column_type") + "）"
                + "，当前值 " + row.get("current_value")
                + "，最大值 " + row.get("max_value")
                + "，剩余空间不足 10%。"
        );
    }

    private String replicationHealth() {
        boolean isReplica = isReplica();
        List<String> lines = new ArrayList<>();
        if (isReplica) {
            lines.add("当前数据库是副本。");
            lines.add(replicationLag());
        } else {
            lines.add("当前数据库是主库。");
            lines.add(replicaConnections());
        }
        return String.join(System.lineSeparator(), lines);
    }

    private boolean isReplica() {
        try {
            QueryResult result;
            if (featureService.mysqlMajorVersion() >= 8) {
                result = sqlClient.query(
                    "SELECT VARIABLE_VALUE AS replica FROM performance_schema.global_variables WHERE VARIABLE_NAME = 'super_read_only'"
                );
            } else {
                result = sqlClient.query("SELECT @@read_only AS replica");
            }
            return truthy(firstValue(result, "replica"));
        } catch (Exception e) {
            return false;
        }
    }

    private String replicationLag() {
        try {
            String statusCommand = featureService.mysqlMajorVersion() >= 8
                ? "SHOW REPLICA STATUS"
                : "SHOW SLAVE STATUS";
            QueryResult result = sqlClient.query(statusCommand);
            if (result.rows().isEmpty()) {
                return "无法读取复制状态。";
            }
            Object lag = result.rows().getFirst().get("Seconds_Behind_Master");
            if (lag == null) {
                lag = result.rows().getFirst().get("Seconds_Behind_Source");
            }
            if (lag == null) {
                return "无法读取复制延迟。";
            }
            return "复制延迟：" + lag + " 秒。";
        } catch (Exception e) {
            return "无法读取复制状态：" + SecretMasker.mask(e.getMessage());
        }
    }

    private String replicaConnections() {
        try {
            QueryResult result = sqlClient.query(
                """
                SELECT COUNT(*) AS count
                FROM information_schema.PROCESSLIST
                WHERE COMMAND = 'Binlog Dump'
                """
            );
            long count = singleLong(result, "count");
            return count > 0 ? "存在活跃副本连接：" + count + "。" : "未发现活跃副本连接。";
        } catch (Exception e) {
            return "无法检查副本连接：" + SecretMasker.mask(e.getMessage());
        }
    }

    private String bufferHealth() {
        try {
            QueryResult reads = sqlClient.query(
                "SELECT VARIABLE_VALUE AS value FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_buffer_pool_reads'"
            );
            QueryResult requests = sqlClient.query(
                "SELECT VARIABLE_VALUE AS value FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_buffer_pool_read_requests'"
            );
            BigDecimal readVal = number(firstValue(reads, "value"));
            BigDecimal requestVal = number(firstValue(requests, "value"));
            if (requestVal.compareTo(BigDecimal.ZERO) == 0) {
                return "InnoDB 缓冲池暂无统计数据。";
            }
            BigDecimal rate = BigDecimal.ONE.subtract(readVal.divide(requestVal, 4, RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
            String relation = rate.compareTo(BigDecimal.valueOf(95)) >= 0 ? "高于" : "低于";
            return "InnoDB 缓冲池命中率：" + rate + "%，" + relation + " 95.0% 阌值。";
        } catch (Exception e) {
            return "缓冲池检查失败：" + SecretMasker.mask(e.getMessage());
        }
    }

    private String constraintHealth() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                CONSTRAINT_SCHEMA AS schema_name,
                CONSTRAINT_NAME AS constraint_name,
                TABLE_NAME AS table_name,
                REFERENCED_TABLE_SCHEMA AS referenced_schema,
                REFERENCED_TABLE_NAME AS referenced_table
            FROM information_schema.REFERENTIAL_CONSTRAINTS
            WHERE CONSTRAINT_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            ORDER BY CONSTRAINT_SCHEMA, TABLE_NAME, CONSTRAINT_NAME
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现外键约束。";
        }
        return "发现 " + result.rows().size() + " 个外键约束；MySQL 不支持未验证约束概念，所有约束默认为已验证。";
    }

    // ==================== 索引建议（规则引擎 + EXPLAIN 基线） ====================

    @Override
    public String analyzeWorkloadIndexes(int maxIndexSizeMb, String method) {
        if (!featureService.isPerformanceSchemaEnabled()) {
            return featureService.performanceSchemaInstallMessage();
        }
        if (usesLlm(method)) {
            return llmDeferredMessage();
        }
        QueryResult result = sqlClient.query(
            """
            SELECT
                DIGEST_TEXT AS query,
                COUNT_STAR AS calls,
                SUM_TIMER_WAIT / NULLIF(COUNT_STAR, 0) / %d AS avg_exec_time_sec
            FROM performance_schema.events_statements_summary_by_digest
            WHERE DIGEST_TEXT IS NOT NULL
                AND COUNT_STAR >= 50
                AND SUM_TIMER_WAIT / NULLIF(COUNT_STAR, 0) / %d >= 5.0
                AND DIGEST_TEXT LIKE 'SELECT%%'
            ORDER BY SUM_TIMER_WAIT DESC
            LIMIT ?
            """.formatted(MYSQL_PERFORMANCE_SCHEMA_TIMER_DIVISOR, MYSQL_PERFORMANCE_SCHEMA_TIMER_DIVISOR),
            List.of(MAX_NUM_INDEX_TUNING_QUERIES)
        );
        List<WorkloadQuery> workload = result.rows().stream()
            .map(row -> new WorkloadQuery(
                String.valueOf(row.get("query")),
                number(row.getOrDefault("calls", 1)).doubleValue() * number(row.getOrDefault("avg_exec_time_sec", 1)).doubleValue()
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

        List<IndexCandidate> scored = scoreCandidates(candidates, plannedQueries, maxIndexSizeMb);
        if (scored.isEmpty()) {
            return "未发现值得推荐的索引。";
        }
        return render(source, scored);
    }

    private PlannedQuery explain(WorkloadQuery workloadQuery) {
        if (!isSelect(workloadQuery.sql())) {
            return null;
        }
        QueryResult result = sqlClient.query("EXPLAIN FORMAT=JSON " + workloadQuery.sql());
        if (result.rows().isEmpty()) {
            return null;
        }
        Object rawPlan = result.rows().getFirst().values().stream().findFirst().orElse(null);
        JsonNode root = parsePlan(rawPlan);
        if (root == null || root.isMissingNode()) {
            return null;
        }
        return parseExplainJson(root, workloadQuery);
    }

    private PlannedQuery parseExplainJson(JsonNode root, WorkloadQuery workloadQuery) {
        JsonNode queryBlock = root.path("query_block");
        double cost = queryBlock.path("cost_info").path("query_cost").asDouble(Double.POSITIVE_INFINITY);
        List<PlanRelation> relations = collectRelations(queryBlock);
        return new PlannedQuery(workloadQuery.sql(), workloadQuery.weight(), cost, relations);
    }

    private List<PlanRelation> collectRelations(JsonNode queryBlock) {
        List<PlanRelation> relations = new ArrayList<>();
        collectRelationsFromNode(queryBlock, relations);
        return relations;
    }

    private void collectRelationsFromNode(JsonNode node, List<PlanRelation> relations) {
        if (node.has("table_name")) {
            String tableName = node.path("table_name").asText();
            String schemaName = node.path("schema_name").asText("");
            if (schemaName.isBlank()) {
                schemaName = currentSchema();
            }
            String accessType = node.path("access_type").asText("");
            String condition = node.path("attached_condition").asText("");
            boolean sequential = "ALL".equalsIgnoreCase(accessType);
            relations.add(new PlanRelation(schemaName, tableName, condition, sequential));
        }
        // MySQL EXPLAIN JSON 中 "table" 是 query_block 的直接子键，需递归处理
        JsonNode table = node.path("table");
        if (table.isObject() && !table.isMissingNode() && table.has("table_name")) {
            collectRelationsFromNode(table, relations);
        }
        JsonNode nestedLoop = node.path("nested_loop");
        if (nestedLoop.isArray()) {
            for (JsonNode item : nestedLoop) {
                collectRelationsFromNode(item, relations);
            }
        }
        JsonNode subqueries = node.path("subqueries");
        if (subqueries.isArray()) {
            for (JsonNode item : subqueries) {
                collectRelationsFromNode(item.path("query_block"), relations);
            }
        }
        JsonNode union = node.path("union_result");
        if (!union.isMissingNode()) {
            collectRelationsFromNode(union, relations);
        }
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
                    Pattern columnPattern = Pattern.compile(
                        "(?i)(^|[^A-Za-z0-9_])" + Pattern.quote(column) + "([^A-Za-z0-9_]|$)"
                    );
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
                    String def = definition(table, List.of(column));
                    candidates.add(new IndexCandidate(table, List.of(column), def, size, 0, 0));
                }
            }
        });
        return candidates;
    }

    private List<IndexCandidate> scoreCandidates(
        List<IndexCandidate> candidates, List<PlannedQuery> plannedQueries, int maxIndexSizeMb
    ) {
        long budgetBytes = maxIndexSizeMb <= 0 ? Long.MAX_VALUE : maxIndexSizeMb * 1024L * 1024L;
        List<IndexCandidate> scored = new ArrayList<>();
        for (IndexCandidate candidate : candidates) {
            if (candidate.estimatedSizeBytes() > budgetBytes) {
                continue;
            }
            double score = computeRuleScore(candidate, plannedQueries);
            if (score > 0) {
                scored.add(candidate.withScore(score));
            }
        }
        return scored.stream()
            .sorted(Comparator.comparingDouble(IndexCandidate::score).reversed())
            .limit(MAX_NUM_INDEX_TUNING_QUERIES)
            .toList();
    }

    /**
     * 规则评分：
     * - 全表扫描查询越多 → 基础分越高
     * - 查询权重越大 → 分越高
     * - 索引大小越小 → 分越高
     */
    private double computeRuleScore(IndexCandidate candidate, List<PlannedQuery> plannedQueries) {
        double weightSum = 0;
        int seqScanCount = 0;
        for (PlannedQuery query : plannedQueries) {
            for (PlanRelation relation : query.relations()) {
                TableRef table = new TableRef(relation.schema(), relation.table());
                if (table.equals(candidate.table())) {
                    if (relation.sequential()) {
                        seqScanCount++;
                        weightSum += query.weight();
                    } else if (relation.conditionText().contains(candidate.columns().getFirst())) {
                        weightSum += query.weight() * 0.5;
                    }
                }
            }
        }
        double baseScore = seqScanCount > 0 ? weightSum * 2 : weightSum;
        long size = candidate.estimatedSizeBytes();
        double sizeFactor = size > 0 ? Math.max(0.1, 1.0 - (double) size / (100L * 1024L * 1024L)) : 1.0;
        return baseScore * sizeFactor;
    }

    private String render(String source, List<IndexCandidate> recommendations) {
        List<String> lines = new ArrayList<>();
        lines.add("{summary={workload_source=" + source
            + ", total_recommendations=" + recommendations.size()
            + ", method=rule_engine"
            + "}, recommendations=[");
        for (int i = 0; i < recommendations.size(); i++) {
            IndexCandidate candidate = recommendations.get(i);
            lines.add("  {index_apply_order=" + (i + 1)
                + ", index_target_table=" + candidate.table().qualifiedName()
                + ", index_target_columns=" + candidate.columns()
                + ", rule_score=" + round(candidate.score())
                + ", reason='" + scoreReason(candidate) + "'"
                + ", index_estimated_size_bytes=" + candidate.estimatedSizeBytes()
                + ", index_definition=" + candidate.definition()
                + "}");
        }
        lines.add("]}");
        return String.join(System.lineSeparator(), lines);
    }

    private static String scoreReason(IndexCandidate candidate) {
        if (candidate.score() >= 10) {
            return "高频全表扫描查询,高优先级索引";
        }
        if (candidate.score() >= 2) {
            return "存在全表扫描,建议添加索引";
        }
        return "查询条件匹配,索引可能有益";
    }

    private Map<TableRef, Set<String>> existingSimpleIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                TABLE_SCHEMA AS schema_name,
                TABLE_NAME AS table_name,
                COLUMN_NAME AS column_name
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                AND SEQ_IN_INDEX = 1
            ORDER BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """
        );
        Map<TableRef, Set<String>> indexes = new LinkedHashMap<>();
        for (Map<String, Object> row : result.rows()) {
            TableRef table = new TableRef(
                String.valueOf(row.get("schema_name")),
                String.valueOf(row.get("table_name"))
            );
            indexes.computeIfAbsent(table, ignored -> new HashSet<>()).add(String.valueOf(row.get("column_name")));
        }
        return indexes;
    }

    private Set<String> tableColumns(TableRef table) {
        QueryResult result = sqlClient.query(
            """
            SELECT COLUMN_NAME
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = ?
                AND TABLE_NAME = ?
                AND DATA_TYPE NOT IN ('text', 'mediumtext', 'longtext', 'json', 'blob', 'mediumblob', 'longblob')
                AND (CHARACTER_MAXIMUM_LENGTH IS NULL OR CHARACTER_MAXIMUM_LENGTH <= 255)
            ORDER BY ORDINAL_POSITION
            """,
            List.of(table.schema(), table.name())
        );
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : result.rows()) {
            columns.add(String.valueOf(row.get("COLUMN_NAME")));
        }
        return columns;
    }

    private long estimateIndexSize(TableRef table, List<String> columns) {
        QueryResult result = sqlClient.query(
            """
            SELECT
                TABLE_ROWS AS row_estimate,
                AVG_ROW_LENGTH AS avg_row_length
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            """,
            List.of(table.schema(), table.name())
        );
        if (result.rows().isEmpty()) {
            return 0;
        }
        Map<String, Object> row = result.rows().getFirst();
        BigDecimal rows = number(row.getOrDefault("row_estimate", 1000)).max(BigDecimal.ONE);
        BigDecimal width = number(row.getOrDefault("avg_row_length", 16)).add(BigDecimal.valueOf(8));
        return rows.multiply(width).multiply(BigDecimal.valueOf(1.2)).longValue();
    }

    private JsonNode parsePlan(Object rawPlan) {
        try {
            if (rawPlan instanceof JsonNode node) {
                return node;
            }
            return objectMapper.readTree(String.valueOf(rawPlan));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法解析 MySQL EXPLAIN JSON 计划", e);
        }
    }

    private long innodbPageSize() {
        try {
            QueryResult result = sqlClient.query(
                "SELECT VARIABLE_VALUE AS value FROM performance_schema.global_variables WHERE VARIABLE_NAME = 'innodb_page_size'"
            );
            Object value = firstValue(result, "value");
            if (value != null) {
                return Long.parseLong(String.valueOf(value));
            }
        } catch (Exception ignored) {
        }
        return 16384;
    }

    private String currentSchema() {
        try {
            QueryResult result = sqlClient.query("SELECT DATABASE() AS schema_name");
            Object schema = firstValue(result, "schema_name");
            return schema == null ? "" : String.valueOf(schema);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 通用辅助 ====================

    private String runCheck(String title, HealthCheck check) {
        try {
            return title + "：" + System.lineSeparator() + check.run();
        } catch (Exception e) {
            return title + "：" + System.lineSeparator() + "检查失败：" + SecretMasker.mask(e.getMessage());
        }
    }

    private static String definition(TableRef table, List<String> columns) {
        String name = "mcp_idx_" + table.name() + "_" + String.join("_", columns);
        String renderedColumns = columns.stream().map(MySqlDiagnosticDialect::quoteIdentifier).collect(Collectors.joining(", "));
        return "CREATE INDEX " + quoteIdentifier(name) + " ON " + table.qualifiedQuotedName() + " (" + renderedColumns + ")";
    }

    private static String quoteIdentifier(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("非法标识符：" + value);
        }
        return "`" + value.replace("`", "``") + "`";
    }

    private static String llmDeferredMessage() {
        return "LLM 索引优化方法保留为后续阶段接入；当前 MySQL 版本已实现 method='dta'（规则引擎评分）。";
    }

    @FunctionalInterface
    private interface HealthCheck {
        String run();
    }

    private record WorkloadQuery(String sql, double weight) {}
    private record PlannedQuery(String sql, double weight, double cost, List<PlanRelation> relations) {}
    private record PlanRelation(String schema, String table, String conditionText, boolean sequential) {}
    private record TableRef(String schema, String name) {
        private String qualifiedName() {
            return (schema == null || schema.isEmpty()) ? name : schema + "." + name;
        }

        private String qualifiedQuotedName() {
            return (schema == null || schema.isEmpty())
                ? quoteIdentifier(name)
                : quoteIdentifier(schema) + "." + quoteIdentifier(name);
        }
    }
    private record IndexCandidate(
        TableRef table, List<String> columns, String definition,
        long estimatedSizeBytes, double baseCost, double score
    ) {
        private IndexCandidate withScore(double newScore) {
            return new IndexCandidate(table, columns, definition, estimatedSizeBytes, baseCost, newScore);
        }
    }
}
