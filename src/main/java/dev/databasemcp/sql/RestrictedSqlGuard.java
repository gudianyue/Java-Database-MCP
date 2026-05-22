package dev.databasemcp.sql;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RestrictedSqlGuard {

    private static final Pattern SQL_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|--[^\\r\\n]*");
    private static final Pattern FIRST_WORD = Pattern.compile("^([a-zA-Z]+)\\b");
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
    private static final Set<String> BLOCKED_WORDS = Set.of(
        "insert",
        "update",
        "delete",
        "drop",
        "alter",
        "truncate",
        "merge",
        "grant",
        "revoke",
        "copy",
        "call",
        "do",
        "set",
        "reset"
    );

    public void validate(String sql) {
        String normalized = normalize(sql);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (containsUnsafeMultiStatement(normalized)) {
            throw new IllegalArgumentException("受限模式拒绝多语句 SQL");
        }
        String firstWord = firstWord(normalized);
        if (BLOCKED_WORDS.contains(firstWord)) {
            throw new IllegalArgumentException("受限模式拒绝 " + firstWord.toUpperCase(Locale.ROOT) + " 语句");
        }
        if ("create".equals(firstWord) && !normalized.startsWith("create extension")) {
            throw new IllegalArgumentException("受限模式下 CREATE 语句只允许 CREATE EXTENSION");
        }
        if (!ALLOWED_FIRST_WORDS.contains(firstWord)) {
            throw new IllegalArgumentException("受限模式不允许 " + firstWord.toUpperCase(Locale.ROOT) + " 语句");
        }
    }

    private static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        return SQL_COMMENT.matcher(sql).replaceAll(" ").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean containsUnsafeMultiStatement(String sql) {
        String stripped = sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql;
        return stripped.contains(";");
    }

    private static String firstWord(String sql) {
        var matcher = FIRST_WORD.matcher(sql);
        return matcher.find() ? matcher.group(1) : "";
    }
}
