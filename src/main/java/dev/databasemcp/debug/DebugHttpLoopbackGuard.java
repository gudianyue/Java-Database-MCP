package dev.databasemcp.debug;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "database-mcp.debug.http.enabled", havingValue = "true")
public class DebugHttpLoopbackGuard {

    private final Environment environment;

    public DebugHttpLoopbackGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verifyLoopbackBinding() {
        String address = environment.getProperty("server.address");
        if (address == null || address.isBlank()) {
            return;
        }
        InetAddress parsed;
        try {
            parsed = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("无法解析 server.address=" + address + "，拒绝启动", e);
        }
        if (!parsed.isLoopbackAddress()) {
            throw new IllegalStateException(
                "debug.http.enabled=true 但 server.address=" + address + " 非 loopback，拒绝启动");
        }
    }
}
