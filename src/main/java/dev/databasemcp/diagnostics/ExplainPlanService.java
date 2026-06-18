package dev.databasemcp.diagnostics;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.dialect.DatabaseDialect;
import dev.databasemcp.dialect.DatabaseDialectProvider;
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
    private final DatabaseDialectProvider dialectProvider;

    public ExplainPlanService(
        SqlClient sqlClient,
        PostgresExtensionService extensionService,
        DatabaseDialectProvider dialectProvider
    ) {
        this.sqlClient = sqlClient;
        this.extensionService = extensionService;
        this.dialectProvider = dialectProvider;
    }

    public String explain(String sql, boolean analyze, List<Map<String, Object>> hypotheticalIndexes) {
        List<Map<String, Object>> indexes = hypotheticalIndexes == null ? List.of() : hypotheticalIndexes;
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (analyze && !indexes.isEmpty()) {
            throw new IllegalArgumentException("analyze 和 hypothetical_indexes 不能同时使用");
        }
        DatabaseDialect dialect = dialectProvider.current();
        if (dialect.databaseType() != DatabaseType.POSTGRESQL) {
            if (analyze) {
                return switch (dialect.databaseType()) {
                    case MYSQL -> "当前 MCP 工具的 MySQL 分支不支持 analyze=true；请使用 analyze=false 查看基础 EXPLAIN 计划。";
                    case DAMENG -> "当前 MCP 工具的达梦分支不支持 analyze=true；请使用 analyze=false 查看基础 EXPLAIN 计划。";
                    default -> "当前数据库类型不支持 analyze=true。";
                };
            }
            if (!indexes.isEmpty()) {
                return switch (dialect.databaseType()) {
                    case MYSQL -> "MySQL 分支不支持 hypothetical_indexes。";
                    case DAMENG -> "达梦分支不支持 hypothetical_indexes。";
                    default -> "当前数据库类型不支持 hypothetical_indexes。";
                };
            }
            if (dialect.databaseType() == DatabaseType.DAMENG) {
                ReadOnlyQueryValidator.validateSelectSingleStatement(sql);
            }
            return renderRows(dialect.explain(sql));
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

    private static String renderRows(QueryResult result) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> row : result.rows()) {
            if (!row.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(System.lineSeparator());
                }
                builder.append(row);
            }
        }
        return builder.length() == 0 ? "[]" : builder.toString();
    }

    private static String toCreateIndexSql(Map<String, Object> index) {
        Object table = index.get("table");
        Object columns = index.get("columns");
        Object using = index.getOrDefault("using", "btree");
        if (table == null || String.valueOf(table).isBlank()) {
            throw new IllegalArgumentException("hypothetical_indexes 的 table 不能为空");
        }
        if (!(columns instanceof List<?> columnList) || columnList.isEmpty()) {
            throw new IllegalArgumentException("hypothetical_indexes 的 columns 必须是非空列表");
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
            throw new IllegalArgumentException("EXPLAIN ANALYZE 不能执行 " + firstWord.toUpperCase(Locale.ROOT) + " 语句");
        }
    }

    private static String hypopgInstallMessage() {
        return """
            评估假设索引需要 hypopg 扩展，但当前数据库未安装。

            可在 PostgreSQL 中执行以下语句安装：CREATE EXTENSION hypopg;

            hypopg 只创建会话级假设索引用于计划评估，不会创建物理索引。
            """.strip();
    }
}
