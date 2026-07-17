package dev.databasemcp.diagnostics;

import static dev.databasemcp.diagnostics.DiagnosticSupport.firstValue;
import static dev.databasemcp.diagnostics.DiagnosticSupport.singleLong;
import static dev.databasemcp.diagnostics.DiagnosticSupport.truthy;

import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import org.springframework.stereotype.Service;

/**
 * MySQL 特性检查服务，用于判断 performance_schema、sys schema 等是否可用，
 * 以及获取 MySQL 版本信息。
 */
@Service
public class MySqlFeatureService {

    private final SqlClient sqlClient;

    public MySqlFeatureService(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    /**
     * 检查 performance_schema 是否可用。
     */
    public boolean isPerformanceSchemaEnabled() {
        try {
            QueryResult result = sqlClient.query("SELECT @@performance_schema AS enabled");
            return truthy(firstValue(result, "enabled"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否有 sys 辅助 schema（MySQL 5.7.7+ / 8.0+）。
     */
    public boolean hasSysSchema() {
        try {
            QueryResult result = sqlClient.query(
                "SELECT COUNT(*) AS cnt FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = 'sys'"
            );
            return singleLong(result, "cnt") > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 MySQL 主要版本号（5, 8 等）。
     */
    public int mysqlMajorVersion() {
        QueryResult result = sqlClient.query("SELECT VERSION() AS version");
        String version = String.valueOf(firstValue(result, "version"));
        return Integer.parseInt(version.split("\\.")[0]);
    }

    String performanceSchemaInstallMessage() {
        return """
            performance_schema 是 MySQL 慢查询统计和部分健康检查所必需的，但当前数据库未启用。

            可以在 my.cnf 中添加：performance_schema = ON
            然后重启 MySQL 服务。

            performance_schema 记录语句执行统计、连接状态、索引使用等信息，是 MySQL 性能诊断的核心基础设施。
            """.strip();
    }

}
