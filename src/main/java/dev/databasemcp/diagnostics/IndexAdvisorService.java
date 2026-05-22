package dev.databasemcp.diagnostics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import org.springframework.stereotype.Service;

@Service
public class IndexAdvisorService {

    private static final int MAX_NUM_INDEX_TUNING_QUERIES = 10;
    private static final String PG_STAT_STATEMENTS = "pg_stat_statements";
    private static final String HYPOPG = "hypopg";

    private final SqlClient sqlClient;
    private final PostgresExtensionService extensionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IndexAdvisorService(SqlClient sqlClient, PostgresExtensionService extensionService) {
        this.sqlClient = sqlClient;
        this.extensionService = extensionService;
    }

    public String analyzeWorkloadIndexes(int maxIndexSizeMb, String method) {
        if (usesLlm(method)) {
            return llmDeferredMessage();
        }
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

    public String analyzeQueryIndexes(List<String> queries, int maxIndexSizeMb, String method) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("请提供非空 SQL 查询列表。");
        }
        if (queries.size() > MAX_NUM_INDEX_TUNING_QUERIES) {
            throw new IllegalArgumentException("请提供最多 " + MAX_NUM_INDEX_TUNING_QUERIES + " 条 SQL 查询。");
        }
        if (usesLlm(method)) {
            return llmDeferredMessage();
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
        QueryResult result = sqlClient.query("SELECT s.last_analyze FROM pg_stat_user_tables s ORDER BY s.last_analyze DESC NULLS LAST LIMIT 1");
        return result.rows().isEmpty() || result.rows().stream().anyMatch(row -> row.get("last_analyze") != null);
    }

    private static boolean isSelect(String sql) {
        return sql != null && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select");
    }

    private static boolean usesLlm(String method) {
        return "llm".equalsIgnoreCase(method == null || method.isBlank() ? "dta" : method);
    }

    private static String definition(TableRef table, List<String> columns, String using) {
        String name = "crystaldba_idx_" + table.name() + "_" + String.join("_", columns);
        String renderedColumns = columns.stream().map(IndexAdvisorService::quoteIdentifier).reduce((left, right) -> left + ", " + right).orElseThrow();
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

    private static String round(double value) {
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return String.valueOf(value);
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number numeric) {
            return BigDecimal.valueOf(numeric.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static String hypopgInstallMessage() {
        return """
            hypopg 扩展是 DTA 索引调优评估所必需的，但当前数据库尚未安装。

            可以在数据库中执行：CREATE EXTENSION hypopg;

            Java 版本会用 HypoPG 创建会话级假设索引并比较 EXPLAIN 成本，不会真正创建物理索引。
            """.strip();
    }

    private static String pgStatStatementsInstallMessage() {
        return "pg_stat_statements 扩展是分析工作负载索引建议所必需的。请先执行：CREATE EXTENSION pg_stat_statements;";
    }

    private static String llmDeferredMessage() {
        return "LLM 索引优化方法保留为后续阶段接入；当前 Java 版本已实现 method='dta'。";
    }

    private record WorkloadQuery(String sql, double weight) {
    }

    private record PlannedQuery(String sql, double weight, double cost, List<PlanRelation> relations) {
    }

    private record PlanRelation(String schema, String table, String conditionText, boolean sequential) {
    }

    private record TableRef(String schema, String name) {
        private String qualifiedName() {
            return schema + "." + name;
        }

        private String qualifiedQuotedName() {
            return quoteIdentifier(schema) + "." + quoteIdentifier(name);
        }
    }

    private record IndexCandidate(
        TableRef table,
        List<String> columns,
        String using,
        String definition,
        long estimatedSizeBytes,
        double baseCost,
        double newCost,
        double improvementMultiple
    ) {
        private IndexCandidate withCosts(double nextBaseCost, double nextNewCost, double nextImprovementMultiple) {
            return new IndexCandidate(table, columns, using, definition, estimatedSizeBytes, nextBaseCost, nextNewCost, nextImprovementMultiple);
        }
    }
}
