package dev.databasemcp.diagnostics;

import dev.databasemcp.sql.SqlTextUtils;
import java.util.Set;

public final class ReadOnlyQueryValidator {

    private static final Set<String> READ_ONLY_QUERY_STARTS = Set.of("select");

    private ReadOnlyQueryValidator() {
    }

    public static void validateSelectSingleStatement(String sql) {
        String source = sql == null ? "" : sql;
        String normalized = SqlTextUtils.normalize(source);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (!SqlTextUtils.isSingleStatement(source)) {
            throw new IllegalArgumentException("只读诊断工具拒绝多语句 SQL");
        }
        String firstWord = SqlTextUtils.firstWord(normalized);
        if (!READ_ONLY_QUERY_STARTS.contains(firstWord)) {
            throw new IllegalArgumentException("只读诊断工具仅支持 SELECT 查询");
        }
    }
}
