package dev.databasemcp.diagnostics;

import org.springframework.stereotype.Service;

/**
 * 慢查询统计服务，作为薄路由层委托到当前数据库类型的 DiagnosticDialect。
 */
@Service
public class TopQueriesService {

    private final DiagnosticDialectProvider dialectProvider;

    public TopQueriesService(DiagnosticDialectProvider dialectProvider) {
        this.dialectProvider = dialectProvider;
    }

    public String getTopQueries(String sortBy, int limit) {
        return dialectProvider.current().getTopQueries(sortBy, limit);
    }
}
