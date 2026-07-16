package dev.databasemcp.mcp;

import dev.databasemcp.diagnostics.DiagnosticDialectProvider;
import dev.databasemcp.diagnostics.ExplainPlanService;
import dev.databasemcp.dialect.DatabaseDialectProvider;
import dev.databasemcp.permission.MetricPermissionEnforcer;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SecretMasker;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import java.util.Map;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class DatabaseToolFacade {

    private final DatabaseDialectProvider databaseDialectProvider;
    private final SqlClient sqlClient;
    private final ExplainPlanService explainPlanService;
    private final DiagnosticDialectProvider diagnosticDialectProvider;
    private final MetricPermissionEnforcer permissionEnforcer;

    public DatabaseToolFacade(
        DatabaseDialectProvider databaseDialectProvider,
        SqlClient sqlClient,
        ExplainPlanService explainPlanService,
        DiagnosticDialectProvider diagnosticDialectProvider,
        MetricPermissionEnforcer permissionEnforcer
    ) {
        this.databaseDialectProvider = databaseDialectProvider;
        this.sqlClient = sqlClient;
        this.explainPlanService = explainPlanService;
        this.diagnosticDialectProvider = diagnosticDialectProvider;
        this.permissionEnforcer = permissionEnforcer;
    }

    @McpTool(name = "list_schemas", description = "列出当前数据库连接可见的 schema")
    public String listSchemas() {
        try {
            return toText(databaseDialectProvider.current().listSchemas());
        } catch (Exception e) {
            return error(e);
        }
    }

    @McpTool(name = "list_objects", description = "列出指定 schema 下的数据库对象")
    public String listObjects(
        @McpToolParam(description = "Schema 名称") String schemaName,
        @McpToolParam(description = "对象类型：table、view、sequence 或 extension") String objectType
    ) {
        try {
            return toText(databaseDialectProvider.current().listObjects(schemaName, defaultObjectType(objectType)));
        } catch (Exception e) {
            return error(e);
        }
    }

    @McpTool(name = "get_object_details", description = "查看数据库对象的详细信息")
    public String getObjectDetails(
        @McpToolParam(description = "Schema 名称") String schemaName,
        @McpToolParam(description = "对象名称") String objectName,
        @McpToolParam(description = "对象类型：table、view、sequence 或 extension") String objectType
    ) {
        try {
            return toText(databaseDialectProvider.current().getObjectDetails(schemaName, objectName, defaultObjectType(objectType)));
        } catch (Exception e) {
            return error(e);
        }
    }

    @McpTool(name = "execute_sql", description = "Execute SQL. Always pass user_id; protected metric scopes are derived from the SQL.")
    public String executeSql(
        @McpToolParam(description = "要执行的 SQL") String sql,
        @McpToolParam(description = "user_id: caller identity supplied by the Agent; always pass this value") String user_id
    ) {
        try {
            permissionEnforcer.authorize(sql, user_id);
            return toText(sqlClient.query(sql));
        } catch (Exception e) {
            return error(e);
        }
    }

    @McpTool(name = "explain_query", description = "Explain SQL. Always pass user_id; protected metric scopes are derived from the SQL before EXPLAIN.")
    public String explainQuery(
        @McpToolParam(description = "要解释的 SQL 查询") String sql,
        @McpToolParam(description = "是否在支持的数据库上执行 analyze 计划；默认 false") Boolean analyze,
        @McpToolParam(description = "在支持的数据库上评估的假设索引；不需要时传空列表") List<Map<String, Object>> hypotheticalIndexes,
        @McpToolParam(description = "user_id: caller identity supplied by the Agent; always pass this value") String user_id
    ) {
        try {
            permissionEnforcer.authorize(sql, user_id);
            return explainPlanService.explain(sql, Boolean.TRUE.equals(analyze), hypotheticalIndexes);
        } catch (Exception e) {
            return error(e);
        }
    }

    @McpTool(name = "get_top_queries", description = "按当前数据库方言报告慢查询或资源消耗较高的查询")
    public String getTopQueries(
        @McpToolParam(description = "排序字段，例如 resources、mean_time、total_time，或数据库方言支持的字段") String sortBy,
        @McpToolParam(description = "最多返回的查询数量；默认 10") Integer limit
    ) {
        try {
            return diagnosticDialectProvider.current().getTopQueries(sortBy, limit == null ? 10 : limit);
        } catch (Exception e) {
            return error(e);
        }
    }

    @McpTool(name = "analyze_db_health", description = "使用当前数据库方言的只读检查分析数据库健康状态")
    public String analyzeDbHealth(
        @McpToolParam(description = "健康检查类型或逗号分隔的类型列表；默认 all") String healthType
    ) {
        try {
            return diagnosticDialectProvider.current().analyzeHealth(healthType == null || healthType.isBlank() ? "all" : healthType);
        } catch (Exception e) {
            return error(e);
        }
    }

    @McpTool(name = "analyze_workload_indexes", description = "分析工作负载查询并给出索引建议")
    public String analyzeWorkloadIndexes(
        @McpToolParam(description = "推荐索引的最大总大小，单位 MB；默认 10000") Integer maxIndexSizeMb,
        @McpToolParam(description = "分析方法：dta 或 llm；当前优先实现 dta") String method
    ) {
        try {
            return diagnosticDialectProvider.current().analyzeWorkloadIndexes(maxIndexSizeMb == null ? 10000 : maxIndexSizeMb, method);
        } catch (Exception e) {
            return error(e);
        }
    }

    @McpTool(name = "analyze_query_indexes", description = "Analyze SQL queries for indexes. Always pass user_id; every query is authorized from its SQL before analysis.")
    public String analyzeQueryIndexes(
        @McpToolParam(description = "要分析的 SQL 查询列表，最多 10 条") List<String> queries,
        @McpToolParam(description = "推荐索引的最大总大小，单位 MB；默认 10000") Integer maxIndexSizeMb,
        @McpToolParam(description = "分析方法：dta 或 llm；当前优先实现 dta") String method,
        @McpToolParam(description = "user_id: caller identity supplied by the Agent; always pass this value") String user_id
    ) {
        try {
            if (queries != null) {
                for (String query : queries) {
                    permissionEnforcer.authorize(query, user_id);
                }
            }
            return diagnosticDialectProvider.current().analyzeQueryIndexes(queries, maxIndexSizeMb == null ? 10000 : maxIndexSizeMb, method);
        } catch (Exception e) {
            return error(e);
        }
    }

    private static String defaultObjectType(String objectType) {
        return objectType == null || objectType.isBlank() ? "table" : objectType;
    }

    private static String toText(QueryResult queryResult) {
        List<Map<String, Object>> rows = queryResult.rows();
        return rows == null || rows.isEmpty() ? "[]" : rows.toString();
    }

    private static String error(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return "错误：" + SecretMasker.mask(message);
    }
}
