package dev.databasemcp.diagnostics;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ReadOnlyQueryValidator {

    private static final Pattern FIRST_WORD = Pattern.compile("^([a-zA-Z]+)\\b");
    private static final Set<String> READ_ONLY_QUERY_STARTS = Set.of("select");

    private ReadOnlyQueryValidator() {
    }

    public static void validateSelectSingleStatement(String sql) {
        String source = sql == null ? "" : sql;
        String normalized = normalize(source);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (containsUnsafeMultiStatement(source)) {
            throw new IllegalArgumentException("只读诊断工具拒绝多语句 SQL");
        }
        String firstWord = firstWord(normalized);
        if (!READ_ONLY_QUERY_STARTS.contains(firstWord)) {
            throw new IllegalArgumentException("只读诊断工具仅支持 SELECT 查询");
        }
    }

    private static String normalize(String sql) {
        return stripComments(sql)
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
    }

    private static boolean containsUnsafeMultiStatement(String sql) {
        ScanState state = new ScanState();
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (state.consume(current, next)) {
                i++;
                continue;
            }
            if (state.isCode() && current == ';' && hasStatementContentAfter(sql, i + 1)) {
                return true;
            }
        }
        return false;
    }

    private static String firstWord(String sql) {
        var matcher = FIRST_WORD.matcher(sql);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String stripComments(String sql) {
        StringBuilder builder = new StringBuilder(sql.length());
        ScanState state = new ScanState();
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (state.consume(current, next)) {
                builder.append(' ');
                if (state.skippedNext()) {
                    builder.append(' ');
                    i++;
                }
                continue;
            }
            builder.append(state.isComment() ? ' ' : current);
        }
        return builder.toString();
    }

    private static boolean hasStatementContentAfter(String sql, int start) {
        ScanState state = new ScanState();
        for (int i = start; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (state.consume(current, next)) {
                i += state.skippedNext() ? 1 : 0;
                continue;
            }
            if (!state.isComment() && !Character.isWhitespace(current)) {
                return true;
            }
        }
        return false;
    }

    private static final class ScanState {
        private boolean singleQuoted;
        private boolean doubleQuoted;
        private boolean lineComment;
        private boolean blockComment;
        private boolean skippedNext;

        boolean consume(char current, char next) {
            skippedNext = false;
            if (lineComment) {
                if (current == '\r' || current == '\n') {
                    lineComment = false;
                }
                return false;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    skippedNext = true;
                    return true;
                }
                return false;
            }
            if (singleQuoted) {
                if (current == '\'' && next == '\'') {
                    skippedNext = true;
                    return true;
                }
                if (current == '\'') {
                    singleQuoted = false;
                }
                return false;
            }
            if (doubleQuoted) {
                if (current == '"' && next == '"') {
                    skippedNext = true;
                    return true;
                }
                if (current == '"') {
                    doubleQuoted = false;
                }
                return false;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                skippedNext = true;
                return true;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                skippedNext = true;
                return true;
            }
            if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                doubleQuoted = true;
            }
            return false;
        }

        boolean skippedNext() {
            return skippedNext;
        }

        boolean isCode() {
            return !singleQuoted && !doubleQuoted && !lineComment && !blockComment;
        }

        boolean isComment() {
            return lineComment || blockComment;
        }
    }
}
