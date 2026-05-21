package dev.postgresmcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;

class PostgresToolFacadeAnnotationTest {

    @Test
    void exposesPhaseAAndPhaseBToolsWithMcpAnnotations() {
        Set<String> toolNames = Arrays.stream(PostgresToolFacade.class.getDeclaredMethods())
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
            "analyze_db_health"
        );
    }
}
