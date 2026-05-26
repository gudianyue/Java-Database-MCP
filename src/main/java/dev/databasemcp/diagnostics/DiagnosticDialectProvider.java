package dev.databasemcp.diagnostics;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 根据配置的数据库类型选择对应的 DiagnosticDialect。
 * 与 DatabaseDialectProvider 模式一致。
 */
@Component
public class DiagnosticDialectProvider {

    private final List<DiagnosticDialect> dialects;
    private final DatabaseMcpProperties properties;

    public DiagnosticDialectProvider(List<DiagnosticDialect> dialects, DatabaseMcpProperties properties) {
        this.dialects = dialects;
        this.properties = properties;
    }

    public DiagnosticDialect current() {
        DatabaseType configured = properties.getDatabaseType();
        return dialects.stream()
            .filter(d -> d.databaseType() == configured)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "未找到数据库类型 " + configured + " 的诊断方言实现"
            ));
    }
}