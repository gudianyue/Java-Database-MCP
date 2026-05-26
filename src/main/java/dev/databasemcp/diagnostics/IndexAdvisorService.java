package dev.databasemcp.diagnostics;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 索引建议服务，作为薄路由层委托到当前数据库类型的 DiagnosticDialect。
 */
@Service
public class IndexAdvisorService {

    private final DiagnosticDialectProvider dialectProvider;

    public IndexAdvisorService(DiagnosticDialectProvider dialectProvider) {
        this.dialectProvider = dialectProvider;
    }

    public String analyzeWorkloadIndexes(int maxIndexSizeMb, String method) {
        return dialectProvider.current().analyzeWorkloadIndexes(maxIndexSizeMb, method);
    }

    public String analyzeQueryIndexes(List<String> queries, int maxIndexSizeMb, String method) {
        return dialectProvider.current().analyzeQueryIndexes(queries, maxIndexSizeMb, method);
    }
}
