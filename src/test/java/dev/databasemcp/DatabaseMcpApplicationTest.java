package dev.databasemcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.mcp.server.enabled=false")
class DatabaseMcpApplicationTest {

    @Test
    void contextLoadsWithoutDatabaseUri() {
    }
}
