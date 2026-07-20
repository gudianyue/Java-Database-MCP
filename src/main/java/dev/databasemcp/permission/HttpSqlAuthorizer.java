package dev.databasemcp.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.databasemcp.config.DatabaseMcpProperties;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "database-mcp.permission.http.url")
class HttpSqlAuthorizer implements SqlAuthorizer {

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final String url;
    private final Duration timeout;

    HttpSqlAuthorizer(
        WebClient.Builder webClientBuilder,
        ObjectMapper objectMapper,
        DatabaseMcpProperties properties
    ) {
        DatabaseMcpProperties.HttpProperties http = properties.getPermission().getHttp();
        if (http.getUrl() == null || http.getUrl().isBlank()) {
            throw new IllegalStateException("database-mcp.permission.http.url must not be blank");
        }
        if (http.getTimeout() == null || http.getTimeout().isZero() || http.getTimeout().isNegative()) {
            throw new IllegalStateException("database-mcp.permission.http.timeout must be greater than zero");
        }
        this.client = webClientBuilder
            .defaultHeaders(headers -> http.getHeaders().forEach(headers::set))
            .build();
        this.objectMapper = objectMapper;
        this.url = http.getUrl();
        this.timeout = http.getTimeout();
    }

    @Override
    public boolean isAllowed(String userId, String sql) {
        JsonNode response;
        try {
            byte[] responseBody = client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AuthorizationRequest(userId, sql))
                .exchangeToMono(httpResponse -> {
                    if (!httpResponse.statusCode().is2xxSuccessful()) {
                        return httpResponse.releaseBody().then(Mono.error(unavailable()));
                    }
                    return httpResponse.bodyToMono(byte[].class)
                        .switchIfEmpty(Mono.error(unavailable()));
                })
                .timeout(timeout)
                .block();
            response = responseBody == null ? null : objectMapper.readTree(responseBody);
        } catch (Exception e) {
            if (hasCause(e, TimeoutException.class)) {
                throw new SqlAuthorizationTimeoutException("HTTP SQL authorizer timed out");
            }
            throw unavailable();
        }
        JsonNode allowed = response == null ? null : response.get("allowed");
        if (response == null || !response.isObject() || response.size() != 1
            || allowed == null || !allowed.isBoolean()) {
            throw unavailable();
        }
        return allowed.booleanValue();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("HTTP SQL authorizer unavailable");
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record AuthorizationRequest(String userId, String sql) {
    }
}
