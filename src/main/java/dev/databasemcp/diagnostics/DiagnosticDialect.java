package dev.databasemcp.diagnostics;

import dev.databasemcp.config.DatabaseType;
import java.util.List;

/**
 * 诊断方言接口，将数据库引擎特定的诊断逻辑从 Service 层解耦。
 * 与 DatabaseDialect 职责分离：DatabaseDialect 处理结构查看和基础 EXPLAIN，
 * DiagnosticDialect 处理慢查询统计、健康检查和索引建议。
 */
public interface DiagnosticDialect {

    DatabaseType databaseType();

    String getTopQueries(String sortBy, int limit);

    String analyzeHealth(String healthType);

    String analyzeWorkloadIndexes(int maxIndexSizeMb, String method);

    String analyzeQueryIndexes(List<String> queries, int maxIndexSizeMb, String method);
}
