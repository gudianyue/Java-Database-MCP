package dev.databasemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

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
    void topQueriesDescriptionUsesGenericDatabaseLanguage() throws NoSuchMethodException {
        McpTool annotation = DatabaseToolFacade.class
            .getDeclaredMethod("getTopQueries", String.class, Integer.class)
            .getAnnotation(McpTool.class);

        assertThat(annotation.description()).isEqualTo("按当前数据库方言报告慢查询或资源消耗较高的查询");
    }

    @Test
    void sqlToolsExposeMetricPermissionParameters() throws NoSuchMethodException {
        assertPermissionParametersVisible(DatabaseToolFacade.class.getDeclaredMethod(
            "executeSql",
            String.class,
            String.class,
            String.class,
            List.class
        ));
        assertPermissionParametersVisible(DatabaseToolFacade.class.getDeclaredMethod(
            "explainQuery",
            String.class,
            Boolean.class,
            List.class,
            String.class,
            String.class,
            List.class
        ));
        assertPermissionParametersVisible(DatabaseToolFacade.class.getDeclaredMethod(
            "analyzeQueryIndexes",
            List.class,
            Integer.class,
            String.class,
            String.class,
            String.class,
            List.class
        ));
    }

    private static void assertPermissionParametersVisible(java.lang.reflect.Method method) {
        String descriptions = Arrays.stream(method.getParameters())
            .map(parameter -> parameter.getAnnotation(McpToolParam.class))
            .filter(annotation -> annotation != null)
            .map(McpToolParam::description)
            .collect(Collectors.joining("\n"));

        assertThat(descriptions)
            .contains("permission_domain")
            .contains("user_id")
            .contains("metric_scopes");
    }
}
