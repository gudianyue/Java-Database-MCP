package dev.databasemcp.mcp;

import dev.databasemcp.diagnostics.DatabaseHealthService;
import dev.databasemcp.diagnostics.ExplainPlanService;
import dev.databasemcp.diagnostics.IndexAdvisorService;
import dev.databasemcp.diagnostics.TopQueriesService;
import dev.databasemcp.schema.SchemaIntrospectionService;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import java.util.Map;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class DatabaseToolFacade {

    private final SchemaIntrospectionService schemaService;
    private final SqlClient sqlClient;
    private final ExplainPlanService explainPlanService;
    private final TopQueriesService topQueriesService;
    private final DatabaseHealthService databaseHealthService;
    private final IndexAdvisorService indexAdvisorService;
    private final ToolResponseMapper mapper;

    public DatabaseToolFacade(
        SchemaIntrospectionService schemaService,
        SqlClient sqlClient,
        ExplainPlanService explainPlanService,
        TopQueriesService topQueriesService,
        DatabaseHealthService databaseHealthService,
        IndexAdvisorService indexAdvisorService,
        ToolResponseMapper mapper
    ) {
        this.schemaService = schemaService;
        this.sqlClient = sqlClient;
        this.explainPlanService = explainPlanService;
        this.topQueriesService = topQueriesService;
        this.databaseHealthService = databaseHealthService;
        this.indexAdvisorService = indexAdvisorService;
        this.mapper = mapper;
    }

    @McpTool(name = "list_schemas", description = "列出数据库中的所有 schema")
    public String listSchemas() {
        try {
            return mapper.toText(schemaService.listSchemas());
        } catch (Exception e) {
            return mapper.error(e);
        }
    }

    @McpTool(name = "list_objects", description = "列出指定 schema 下的对象")
    public String listObjects(
        @McpToolParam(description = "Schema 名称") String schemaName,
        @McpToolParam(description = "对象类型：'table'、'view'、'sequence' 或 'extension'") String objectType
    ) {
        try {
            return mapper.toText(schemaService.listObjects(schemaName, objectType == null || objectType.isBlank() ? "table" : objectType));
        } catch (Exception e) {
            return mapper.error(e);
        }
    }

    @McpTool(name = "get_object_details", description = "查看数据库对象的详细信息")
    public String getObjectDetails(
        @McpToolParam(description = "Schema 名称") String schemaName,
        @McpToolParam(description = "对象名称") String objectName,
        @McpToolParam(description = "对象类型：'table'、'view'、'sequence' 或 'extension'") String objectType
    ) {
        try {
            return mapper.toText(schemaService.getObjectDetails(schemaName, objectName, objectType == null || objectType.isBlank() ? "table" : objectType));
        } catch (Exception e) {
            return mapper.error(e);
        }
    }

    @McpTool(name = "execute_sql", description = "按照配置的访问模式执行 SQL")
    public String executeSql(@McpToolParam(description = "要执行的 SQL") String sql) {
        try {
            return mapper.toText(sqlClient.query(sql));
        } catch (Exception e) {
            return mapper.error(e);
        }
    }

    @McpTool(name = "explain_query", description = "查看 SQL 查询的执行计划和成本估算")
    public String explainQuery(
        @McpToolParam(description = "要解释的 SQL 查询") String sql,
        @McpToolParam(description = "为 true 时实际执行查询并返回真实执行统计；默认 false") Boolean analyze,
        @McpToolParam(description = "要模拟的假设索引列表；没有假设索引时传空列表") List<Map<String, Object>> hypotheticalIndexes
    ) {
        try {
            return explainPlanService.explain(sql, Boolean.TRUE.equals(analyze), hypotheticalIndexes);
        } catch (Exception e) {
            return mapper.error(e);
        }
    }

    @McpTool(name = "get_top_queries", description = "基于 pg_stat_statements 报告慢查询或资源消耗较高的查询")
    public String getTopQueries(
        @McpToolParam(description = "排序条件：'resources'、'mean_time' 或 'total_time'；默认 resources") String sortBy,
        @McpToolParam(description = "按 mean_time 或 total_time 排序时返回的查询数量；默认 10") Integer limit
    ) {
        try {
            return topQueriesService.getTopQueries(sortBy, limit == null ? 10 : limit);
        } catch (Exception e) {
            return mapper.error(e);
        }
    }

    @McpTool(name = "analyze_db_health", description = "分析数据库健康状态，可检查索引、连接、vacuum、序列、复制、缓冲区和约束")
    public String analyzeDbHealth(
        @McpToolParam(description = "健康检查类型：index、connection、vacuum、sequence、replication、buffer、constraint、all；可用逗号组合，默认 all")
        String healthType
    ) {
        try {
            return databaseHealthService.analyze(healthType == null || healthType.isBlank() ? "all" : healthType);
        } catch (Exception e) {
            return mapper.error(e);
        }
    }

    @McpTool(name = "analyze_workload_indexes", description = "分析数据库工作负载并推荐索引")
    public String analyzeWorkloadIndexes(
        @McpToolParam(description = "推荐索引的最大总大小，单位 MB；默认 10000") Integer maxIndexSizeMb,
        @McpToolParam(description = "分析方法：'dta' 或 'llm'；当前 Java 版本优先实现 dta") String method
    ) {
        try {
            return indexAdvisorService.analyzeWorkloadIndexes(maxIndexSizeMb == null ? 10000 : maxIndexSizeMb, method);
        } catch (Exception e) {
            return mapper.error(e);
        }
    }

    @McpTool(name = "analyze_query_indexes", description = "分析最多 10 条 SQL 查询并推荐索引")
    public String analyzeQueryIndexes(
        @McpToolParam(description = "要分析的 SQL 查询列表，最多 10 条") List<String> queries,
        @McpToolParam(description = "推荐索引的最大总大小，单位 MB；默认 10000") Integer maxIndexSizeMb,
        @McpToolParam(description = "分析方法：'dta' 或 'llm'；当前 Java 版本优先实现 dta") String method
    ) {
        try {
            return indexAdvisorService.analyzeQueryIndexes(queries, maxIndexSizeMb == null ? 10000 : maxIndexSizeMb, method);
        } catch (Exception e) {
            return mapper.error(e);
        }
    }
}
