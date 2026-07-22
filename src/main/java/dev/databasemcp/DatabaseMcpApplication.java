package dev.databasemcp;

import dev.databasemcp.config.DatabaseMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Spring Boot 应用入口，装配数据库 MCP 工具与传输层。 */
@SpringBootApplication
@EnableConfigurationProperties(DatabaseMcpProperties.class)
public class DatabaseMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatabaseMcpApplication.class, args);
    }
}
