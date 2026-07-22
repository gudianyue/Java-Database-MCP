package dev.databasemcp.sql;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SqlTextUtils {

    private static final Pattern FIRST_WORD = Pattern.compile("^([a-zA-Z]+)\\b");

    private SqlTextUtils() {
    }

    public static String normalize(String sql) {
        return stripComments(sql == null ? "" : sql)
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
    }

    public static String firstWord(String sql) {
        var matcher = FIRST_WORD.matcher(sql);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static boolean isSingleStatement(String sql) {
        String source = sql == null ? "" : sql;
        ScanState state = new ScanState();
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (state.consume(current, next)) {
                i++;
                continue;
            }
            if (state.isCode() && current == ';' && hasStatementContentAfter(source, i + 1)) {
                return false;
            }
        }
        return true;
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
