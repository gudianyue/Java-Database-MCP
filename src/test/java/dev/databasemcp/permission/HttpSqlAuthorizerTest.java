package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.databasemcp.config.DatabaseMcpProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.reactive.function.client.WebClient;

@ExtendWith(OutputCaptureExtension.class)
class HttpSqlAuthorizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsOriginalRequestAndConfiguredHeaders() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        String url = startServer(exchange -> {
            method.set(exchange.getRequestMethod());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            respond(exchange, 200, "{\"allowed\":true}");
        });
        String userId = " user-1 ";
        String sql = "  select 1  ";
        HttpSqlAuthorizer authorizer = authorizer(
            url,
            Duration.ofSeconds(3),
            Map.of("Authorization", "Bearer secret-token", "X-Api-Key", "secret-key")
        );

        assertThat(authorizer.isAllowed(userId, sql)).isTrue();

        JsonNode json = objectMapper.readTree(requestBody.get());
        assertThat(method).hasValue("POST");
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.path("userId").asText()).isEqualTo(userId);
        assertThat(json.path("sql").asText()).isEqualTo(sql);
        assertThat(authorization).hasValue("Bearer secret-token");
        assertThat(apiKey).hasValue("secret-key");
    }

    @Test
    void returnsFalseForExplicitRemoteDenial() throws Exception {
        String url = startServer(exchange -> respond(exchange, 200, "{\"allowed\":false}"));

        assertThat(authorizer(url, Duration.ofSeconds(3), Map.of()).isAllowed("user-1", "select 1"))
            .isFalse();
    }

    @Test
    void acceptsValidJsonWithoutResponseContentType() throws Exception {
        String url = startServer(exchange -> respondWithoutContentType(exchange, 200, "{\"allowed\":true}"));

        assertThat(authorizer(url, Duration.ofSeconds(3), Map.of()).isAllowed("user-1", "select 1"))
            .isTrue();
    }

    @Test
    void rejectsNon2xxOnceWithoutLeakingRemoteBody() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String url = startServer(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 403, "remote-secret-body");
        });

        assertThatThrownBy(() -> authorizer(url, Duration.ofSeconds(3), Map.of())
            .isAllowed("user-1", "select 1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("HTTP SQL authorizer unavailable")
            .hasMessageNotContaining("remote-secret-body");
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "{}",
        "{\"allowed\":\"true\"}",
        "{\"allowed\":true,\"extra\":1}",
        "not-json"
    })
    void rejectsInvalidResponseBody(String responseBody) throws Exception {
        String url = startServer(exchange -> respond(exchange, 200, responseBody));

        assertThatThrownBy(() -> authorizer(url, Duration.ofSeconds(3), Map.of())
            .isAllowed("user-1", "select 1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("HTTP SQL authorizer unavailable");
    }

    @Test
    void mapsTotalTimeoutToPublicTimeoutWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String url = startServer(exchange -> {
            calls.incrementAndGet();
            LockSupport.parkNanos(Duration.ofMillis(250).toNanos());
            respond(exchange, 200, "{\"allowed\":true}");
        });

        assertThatThrownBy(() -> authorizer(url, Duration.ofMillis(50), Map.of())
            .isAllowed("user-1", "select 1"))
            .isInstanceOf(SqlAuthorizationTimeoutException.class)
            .hasMessage("HTTP SQL authorizer timed out");
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsConnectionFailure() {
        HttpSqlAuthorizer authorizer = authorizer(
            "http://127.0.0.1:1/authorize",
            Duration.ofSeconds(1),
            Map.of()
        );

        assertThatThrownBy(() -> authorizer.isAllowed("user-1", "select 1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("HTTP SQL authorizer unavailable");
    }

    @Test
    void doesNotCacheRepeatedAuthorizationRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String url = startServer(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "{\"allowed\":true}");
        });
        HttpSqlAuthorizer authorizer = authorizer(url, Duration.ofSeconds(3), Map.of());

        assertThat(authorizer.isAllowed("user-1", "select 1")).isTrue();
        assertThat(authorizer.isAllowed("user-1", "select 1")).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void genericFailureLogExcludesHeadersAndRemoteBody(CapturedOutput output) throws Exception {
        String url = startServer(exchange -> respond(exchange, 403, "remote-body-marker"));
        DatabaseMcpProperties properties = properties(
            url,
            Duration.ofSeconds(3),
            Map.of("Authorization", "header-secret-marker")
        );
        properties.getPermission().setEnabled(true);
        SqlAuthorizationEnforcer enforcer = new SqlAuthorizationEnforcer(
            properties,
            new HttpSqlAuthorizer(WebClient.builder(), objectMapper, properties)
        );
        String userId = "user-log-marker";
        String sql = "select sql-log-marker";

        assertThatThrownBy(() -> enforcer.authorize(sql, userId))
            .isInstanceOf(PermissionDeniedException.class)
            .hasMessage("permission_authorizer_unavailable");
        assertThat(output)
            .contains("ERROR")
            .contains("permission_authorizer_unavailable")
            .contains(userId)
            .contains(sql)
            .contains("elapsedMs=")
            .doesNotContain("header-secret-marker")
            .doesNotContain("remote-body-marker");
    }

    private HttpSqlAuthorizer authorizer(String url, Duration timeout, Map<String, String> headers) {
        return new HttpSqlAuthorizer(WebClient.builder(), objectMapper, properties(url, timeout, headers));
    }

    private static DatabaseMcpProperties properties(String url, Duration timeout, Map<String, String> headers) {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.getPermission().getHttp().setUrl(url);
        properties.getPermission().getHttp().setTimeout(timeout);
        properties.getPermission().getHttp().setHeaders(headers);
        return properties;
    }

    private String startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/authorize", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/authorize";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
        throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        respondWithoutContentType(exchange, status, body);
    }

    private static void respondWithoutContentType(
        com.sun.net.httpserver.HttpExchange exchange,
        int status,
        String body
    ) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
