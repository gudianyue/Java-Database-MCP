package dev.databasemcp;

import dev.databasemcp.config.DatabaseMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DatabaseMcpProperties.class)
public class DatabaseMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatabaseMcpApplication.class, args);
    }
}
