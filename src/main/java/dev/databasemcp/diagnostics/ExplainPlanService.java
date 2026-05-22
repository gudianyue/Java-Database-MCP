package dev.databasemcp.diagnostics;

import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ExplainPlanService {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> INDEX_METHODS = Set.of("btree", "hash", "gist", "spgist", "gin", "brin");
    private static final Set<String> ANALYZE_BLOCKED_FIRST_WORDS = Set.of("insert", "update", "delete", "merge", "copy", "call");

    private final SqlClient sqlClient;
    private final PostgresExtensionService extensionService;

    public ExplainPlanService(SqlClient sqlClient, PostgresExtensionService extensionService) {
        this.sqlClient = sqlClient;
        this.extensionService = extensionService;
    }

    public String explain(String sql, boolean analyze, List<Map<String, Object>> hypotheticalIndexes) {
        List<Map<String, Object>> indexes = hypotheticalIndexes == null ? List.of() : hypotheticalIndexes;
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (analyze && !indexes.isEmpty()) {
            throw new IllegalArgumentException("不能同时使用 analyze 和 hypothetical_indexes");
        }
        if (analyze) {
            rejectMutatingAnalyze(sql);
        }
        if (indexes.isEmpty()) {
            return renderPlan(runExplain(sql, analyze));
        }
        if (!extensionService.isExtensionInstalled("hypopg")) {
            return hypopgInstallMessage();
        }
        return explainWithHypotheticalIndexes(sql, indexes);
    }

    private String explainWithHypotheticalIndexes(String sql, List<Map<String, Object>> indexes) {
        try {
            sqlClient.query("SELECT hypopg_reset()");
            for (Map<String, Object> index : indexes) {
                sqlClient.query("SELECT * FROM hypopg_create_index(?)", List.of(toCreateIndexSql(index)));
            }
            return renderPlan(runExplain(sql, false));
        } finally {
            sqlClient.query("SELECT hypopg_reset()");
        }
    }

    private QueryResult runExplain(String sql, boolean analyze) {
        String options = analyze ? "ANALYZE, BUFFERS, FORMAT TEXT" : "FORMAT TEXT";
        return sqlClient.query("EXPLAIN (" + options + ") " + sql);
    }

    private static String renderPlan(QueryResult result) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> row : result.rows()) {
            if (!row.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(System.lineSeparator());
                }
                builder.append(row.values().iterator().next());
            }
        }
        return builder.length() == 0 ? "[]" : builder.toString();
    }

    private static String toCreateIndexSql(Map<String, Object> index) {
        Object table = index.get("table");
        Object columns = index.get("columns");
        Object using = index.getOrDefault("using", "btree");
        if (table == null || String.valueOf(table).isBlank()) {
            throw new IllegalArgumentException("hypothetical_indexes 中的 table 不能为空");
        }
        if (!(columns instanceof List<?> columnList) || columnList.isEmpty()) {
            throw new IllegalArgumentException("hypothetical_indexes 中的 columns 必须是非空列表");
        }
        String method = String.valueOf(using).toLowerCase(Locale.ROOT);
        if (!INDEX_METHODS.contains(method)) {
            throw new IllegalArgumentException("不支持的索引方法：" + using);
        }
        String renderedColumns = columnList.stream()
            .map(column -> quoteIdentifier(String.valueOf(column)))
            .reduce((left, right) -> left + ", " + right)
            .orElseThrow();
        return "CREATE INDEX ON " + quoteQualifiedIdentifier(String.valueOf(table)) + " USING " + method + " (" + renderedColumns + ")";
    }

    private static String quoteQualifiedIdentifier(String value) {
        return java.util.Arrays.stream(value.split("\\."))
            .map(ExplainPlanService::quoteIdentifier)
            .reduce((left, right) -> left + "." + right)
            .orElseThrow();
    }

    private static String quoteIdentifier(String value) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法标识符：" + value);
        }
        return "\"" + value + "\"";
    }

    private static void rejectMutatingAnalyze(String sql) {
        String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
        String firstWord = normalized.split("\\s+", 2)[0];
        if (ANALYZE_BLOCKED_FIRST_WORDS.contains(firstWord)) {
            throw new IllegalArgumentException("EXPLAIN ANALYZE 不允许执行 " + firstWord.toUpperCase(Locale.ROOT) + " 语句");
        }
    }

    private static String hypopgInstallMessage() {
        return """
            hypopg 扩展是测试假设索引所必需的，但当前数据库尚未安装。

            可以在数据库中执行：CREATE EXTENSION hypopg;

            它通常用于索引方案评估，只创建会话级的假设索引，不会真正创建物理索引。
            """.strip();
    }
}
