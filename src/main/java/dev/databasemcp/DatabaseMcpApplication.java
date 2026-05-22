package dev.databasemcp;

import dev.databasemcp.config.PostgresMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PostgresMcpProperties.class)
public class DatabaseMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatabaseMcpApplication.class, args);
    }
}
