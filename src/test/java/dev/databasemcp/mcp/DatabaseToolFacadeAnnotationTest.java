package dev.databasemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.method.tool.utils.JsonSchemaGenerator;

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
    void queryIndexMethodSchemaOnlyAdvertisesDta() throws Exception {
        Method method = DatabaseToolFacade.class.getDeclaredMethod(
            "analyzeQueryIndexes",
            List.class,
            Integer.class,
            String.class,
            String.class
        );

        assertThat(methodParameterDescription(method)).isEqualTo("分析方法：仅支持 dta；为空时默认 dta");
    }

    @Test
    void workloadIndexMethodSchemaOnlyAdvertisesDta() throws Exception {
        Method method = DatabaseToolFacade.class.getDeclaredMethod(
            "analyzeWorkloadIndexes",
            Integer.class,
            String.class
        );

        assertThat(methodParameterDescription(method)).isEqualTo("分析方法：仅支持 dta；为空时默认 dta");
    }

    @Test
    void sqlToolsExposeUserIdentityOnly() throws NoSuchMethodException {
        assertPermissionParametersVisible(DatabaseToolFacade.class.getDeclaredMethod(
            "executeSql",
            String.class,
            String.class
        ));
        assertPermissionParametersVisible(DatabaseToolFacade.class.getDeclaredMethod(
            "explainQuery",
            String.class,
            Boolean.class,
            List.class,
            String.class
        ));
        assertPermissionParametersVisible(DatabaseToolFacade.class.getDeclaredMethod(
            "analyzeQueryIndexes",
            List.class,
            Integer.class,
            String.class,
            String.class
        ));

        assertThat(Arrays.stream(DatabaseToolFacade.class.getDeclaredMethods())
            .filter(method -> Set.of("executeSql", "explainQuery", "analyzeQueryIndexes").contains(method.getName())))
            .hasSize(3);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("permissionProtectedMethods")
    void permissionProtectedToolsExposeSnakeCaseUserIdSchemaProperty(Method method) throws Exception {
        JsonNode properties = new ObjectMapper()
            .readTree(JsonSchemaGenerator.generateForMethodInput(method))
            .path("properties");

        assertThat(properties.has("user_id"))
            .as("%s schema properties should contain user_id", method.getName())
            .isTrue();
        assertThat(properties.has("userId"))
            .as("%s schema properties should not contain userId", method.getName())
            .isFalse();
        assertThat(method.getParameters()[method.getParameterCount() - 1].getName())
            .as("%s final Java parameter name", method.getName())
            .isEqualTo("user_id");
    }

    private static Stream<Method> permissionProtectedMethods() throws NoSuchMethodException {
        return Stream.of(
            DatabaseToolFacade.class.getDeclaredMethod("executeSql", String.class, String.class),
            DatabaseToolFacade.class.getDeclaredMethod("explainQuery", String.class, Boolean.class, List.class, String.class),
            DatabaseToolFacade.class.getDeclaredMethod("analyzeQueryIndexes", List.class, Integer.class, String.class, String.class)
        );
    }

    private static String methodParameterDescription(Method method) throws Exception {
        return new ObjectMapper()
            .readTree(JsonSchemaGenerator.generateForMethodInput(method))
            .path("properties")
            .path("method")
            .path("description")
            .asText();
    }

    private static void assertPermissionParametersVisible(java.lang.reflect.Method method) {
        String descriptions = Arrays.stream(method.getParameters())
            .map(parameter -> parameter.getAnnotation(McpToolParam.class))
            .filter(annotation -> annotation != null)
            .map(McpToolParam::description)
            .collect(Collectors.joining("\n"));

        assertThat(descriptions)
            .contains("user_id")
            .doesNotContain("permission_domain")
            .doesNotContain("metric_scopes");
    }
}
