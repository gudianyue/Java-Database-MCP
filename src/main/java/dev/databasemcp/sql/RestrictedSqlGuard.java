package dev.databasemcp.sql;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RestrictedSqlGuard {

    private static final Set<String> ALLOWED_FIRST_WORDS = Set.of(
        "select",
        "show",
        "explain",
        "with",
        "prepare",
        "deallocate",
        "declare",
        "close",
        "fetch",
        "vacuum",
        "analyze",
        "create"
    );

    public void validate(String sql) {
        String source = sql == null ? "" : sql;
        String normalized = SqlTextUtils.normalize(source);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (!SqlTextUtils.isSingleStatement(source)) {
            throw new IllegalArgumentException("受限模式拒绝多语句 SQL");
        }
        String firstWord = SqlTextUtils.firstWord(normalized);
        if ("create".equals(firstWord) && !normalized.startsWith("create extension")) {
            throw new IllegalArgumentException("受限模式下 CREATE 语句只允许 CREATE EXTENSION");
        }
        if (!ALLOWED_FIRST_WORDS.contains(firstWord)) {
            throw new IllegalArgumentException("受限模式不允许 " + firstWord.toUpperCase(Locale.ROOT) + " 语句");
        }
    }
}
