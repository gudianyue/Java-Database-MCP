package dev.databasemcp.sql;

import java.util.List;
import java.util.Map;

/** SQL 查询结果，行为列名到值的映射列表。 */
public record QueryResult(List<Map<String, Object>> rows) {

    public static QueryResult empty() {
        return new QueryResult(List.of());
    }
}
