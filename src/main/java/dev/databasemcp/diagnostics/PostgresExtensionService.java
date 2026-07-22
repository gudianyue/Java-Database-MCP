package dev.databasemcp.diagnostics;

import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** PostgreSQL 扩展与版本探针，检查 hypopg 等扩展是否安装。 */
@Service
public class PostgresExtensionService {

    private final SqlClient sqlClient;

    public PostgresExtensionService(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public boolean isExtensionInstalled(String extensionName) {
        QueryResult result = sqlClient.query(
            """
            SELECT EXISTS (
                SELECT 1
                FROM pg_extension
                WHERE extname = ?
            ) AS installed
            """,
            List.of(extensionName)
        );
        if (result.rows().isEmpty()) {
            return false;
        }
        Object installed = result.rows().getFirst().get("installed");
        return Boolean.TRUE.equals(installed) || "true".equalsIgnoreCase(String.valueOf(installed));
    }

    public int postgresMajorVersion() {
        QueryResult result = sqlClient.query("SELECT current_setting('server_version_num') AS server_version_num");
        if (result.rows().isEmpty()) {
            throw new IllegalStateException("无法读取 PostgreSQL 版本号");
        }
        Map<String, Object> row = result.rows().getFirst();
        Object rawVersion = row.get("server_version_num");
        int versionNumber = Integer.parseInt(String.valueOf(rawVersion));
        return versionNumber / 10000;
    }
}
