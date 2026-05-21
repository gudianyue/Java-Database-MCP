package dev.postgresmcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.mcp.server.enabled=false")
class PostgresMcpApplicationTest {

    @Test
    void contextLoadsWithoutDatabaseUri() {
    }
}
