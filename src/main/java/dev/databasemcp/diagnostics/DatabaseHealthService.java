package dev.databasemcp.diagnostics;

import org.springframework.stereotype.Service;

/**
 * 健康检查服务，作为薄路由层委托到当前数据库类型的 DiagnosticDialect。
 */
@Service
public class DatabaseHealthService {

    private final DiagnosticDialectProvider dialectProvider;

    public DatabaseHealthService(DiagnosticDialectProvider dialectProvider) {
        this.dialectProvider = dialectProvider;
    }

    public String analyze(String healthType) {
        return dialectProvider.current().analyzeHealth(healthType);
    }
}
