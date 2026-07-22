package dev.databasemcp.debug;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.databasemcp.mcp.DatabaseToolFacade;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = DebugHttpAdapter.class)
@TestPropertySource(properties = "database-mcp.debug.http.enabled=true")
class DebugHttpAdapterTest {

    @MockitoBean
    private DatabaseToolFacade facade;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void listSchemasDelegatesToFacadeAndReturnsOutput() {
        when(facade.listSchemas()).thenReturn("[{schema_name=public}]");

        webTestClient.post().uri("/api/debug/list_schemas")
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.output").isEqualTo("[{schema_name=public}]");

        verify(facade).listSchemas();
    }

    @Test
    void executeSqlBindsBodyAndDelegatesWithUserId() {
        when(facade.executeSql("select 1", "user-1")).thenReturn("ok");

        webTestClient.post().uri("/api/debug/execute_sql")
            .bodyValue(Map.of("sql", "select 1", "user_id", "user-1"))
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.output").isEqualTo("ok");

        verify(facade).executeSql("select 1", "user-1");
    }

    @Test
    void explainQueryBindsComplexBodyAndDelegates() {
        List<Map<String, Object>> indexes = List.of(Map.of("table", "t", "columns", List.of("a")));
        when(facade.explainQuery("select 1", true, indexes, "user-1")).thenReturn("plan");

        webTestClient.post().uri("/api/debug/explain_query")
            .bodyValue(Map.of(
                "sql", "select 1",
                "analyze", true,
                "hypotheticalIndexes", indexes,
                "user_id", "user-1"
            ))
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.output").isEqualTo("plan");

        verify(facade).explainQuery("select 1", true, indexes, "user-1");
    }
}
