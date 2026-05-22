package dev.databasemcp.sql;

import java.util.List;
import java.util.Map;

public record QueryResult(List<Map<String, Object>> rows) {

    public static QueryResult empty() {
        return new QueryResult(List.of());
    }
}
