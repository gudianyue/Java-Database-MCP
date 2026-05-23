package dev.databasemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;

class DatabaseToolFacadeAnnotationTest {

    @Test
    void exposesPhaseAAndPhaseBToolsWithMcpAnnotations() {
        Set<String> toolNames = Arrays.stream(DatabaseToolFacade.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(McpTool.class))
            .filter(annotation -> annotation != null)
            .map(McpTool::name)
            .collect(Collectors.toSet());

        assertThat(toolNames).containsExactlyInAnyOrder(
            "execute_sql",
            "list_schemas",
            "list_objects",
            "get_object_details",
            "explain_query",
            "get_top_queries",
            "analyze_db_health",
            "analyze_workload_indexes",
            "analyze_query_indexes"
        );
    }

    @Test
    void describesTopQueriesWithGenericDatabaseMcpLanguage() throws NoSuchMethodException {
        McpTool annotation = DatabaseToolFacade.class
            .getDeclaredMethod("getTopQueries", String.class, Integer.class)
            .getAnnotation(McpTool.class);

        assertThat(annotation.description()).isEqualTo("报告慢查询或资源消耗较高的查询；当前 PostgreSQL 模式基于 pg_stat_statements");
    }
}
