package dev.postgresmcp;

import dev.postgresmcp.config.PostgresMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PostgresMcpProperties.class)
public class PostgresMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(PostgresMcpApplication.class, args);
    }
}
