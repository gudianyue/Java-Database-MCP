package dev.databasemcp.debug;

import dev.databasemcp.mcp.DatabaseToolFacade;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 调试用 REST 传输适配器，镜像 9 个 MCP 工具，不含业务逻辑。 */
// ponytail: 传输适配器——只镜像门面，禁止转换或状态码映射；任何改造都会让 debug 不再等价于 MCP。
@RestController
@RequestMapping("/api/debug")
@ConditionalOnProperty(name = "database-mcp.debug.http.enabled", havingValue = "true")
public class DebugHttpAdapter {

    private final DatabaseToolFacade facade;

    public DebugHttpAdapter(DatabaseToolFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/list_schemas")
    public Map<String, String> listSchemas() {
        return Map.of("output", facade.listSchemas());
    }

    @PostMapping("/list_objects")
    public Map<String, String> listObjects(@RequestBody ListObjectsRequest request) {
        return Map.of("output", facade.listObjects(request.schemaName(), request.objectType()));
    }

    @PostMapping("/get_object_details")
    public Map<String, String> getObjectDetails(@RequestBody GetObjectDetailsRequest request) {
        return Map.of("output", facade.getObjectDetails(request.schemaName(), request.objectName(), request.objectType()));
    }

    @PostMapping("/execute_sql")
    public Map<String, String> executeSql(@RequestBody ExecuteSqlRequest request) {
        return Map.of("output", facade.executeSql(request.sql(), request.user_id()));
    }

    @PostMapping("/explain_query")
    public Map<String, String> explainQuery(@RequestBody ExplainQueryRequest request) {
        return Map.of("output", facade.explainQuery(request.sql(), request.analyze(), request.hypotheticalIndexes(), request.user_id()));
    }

    @PostMapping("/get_top_queries")
    public Map<String, String> getTopQueries(@RequestBody GetTopQueriesRequest request) {
        return Map.of("output", facade.getTopQueries(request.sortBy(), request.limit()));
    }

    @PostMapping("/analyze_db_health")
    public Map<String, String> analyzeDbHealth(@RequestBody AnalyzeDbHealthRequest request) {
        return Map.of("output", facade.analyzeDbHealth(request.healthType()));
    }

    @PostMapping("/analyze_workload_indexes")
    public Map<String, String> analyzeWorkloadIndexes(@RequestBody AnalyzeWorkloadIndexesRequest request) {
        return Map.of("output", facade.analyzeWorkloadIndexes(request.maxIndexSizeMb(), request.method()));
    }

    @PostMapping("/analyze_query_indexes")
    public Map<String, String> analyzeQueryIndexes(@RequestBody AnalyzeQueryIndexesRequest request) {
        return Map.of("output", facade.analyzeQueryIndexes(request.queries(), request.maxIndexSizeMb(), request.method(), request.user_id()));
    }

    public record ListObjectsRequest(String schemaName, String objectType) {
    }

    public record GetObjectDetailsRequest(String schemaName, String objectName, String objectType) {
    }

    public record ExecuteSqlRequest(String sql, String user_id) {
    }

    public record ExplainQueryRequest(
        String sql,
        Boolean analyze,
        List<Map<String, Object>> hypotheticalIndexes,
        String user_id
    ) {
    }

    public record GetTopQueriesRequest(String sortBy, Integer limit) {
    }

    public record AnalyzeDbHealthRequest(String healthType) {
    }

    public record AnalyzeWorkloadIndexesRequest(Integer maxIndexSizeMb, String method) {
    }

    public record AnalyzeQueryIndexesRequest(
        List<String> queries,
        Integer maxIndexSizeMb,
        String method,
        String user_id
    ) {
    }
}
