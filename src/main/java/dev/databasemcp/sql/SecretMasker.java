package dev.databasemcp.sql;

import java.util.regex.Pattern;

public final class SecretMasker {

    private static final Pattern URL_PASSWORD = Pattern.compile(
        "((?:postgres(?:ql)?|mysql)://[^:\\s]+:)([^@\\s]+)(@[^/\\s]+)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern QUERY_PASSWORD =
        Pattern.compile("([?&]password=)([^&\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAM_PASSWORD =
        Pattern.compile("(password=)([^\\s&;\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_QUOTED_PASSWORD =
        Pattern.compile("(password\\s*=\\s*')([^']+)(')", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOUBLE_QUOTED_PASSWORD =
        Pattern.compile("(password\\s*=\\s*\")([^\"]+)(\")", Pattern.CASE_INSENSITIVE);

    private SecretMasker() {
    }

    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = URL_PASSWORD.matcher(value).replaceAll("$1****$3");
        masked = QUERY_PASSWORD.matcher(masked).replaceAll("$1****");
        masked = PARAM_PASSWORD.matcher(masked).replaceAll("$1****");
        masked = SINGLE_QUOTED_PASSWORD.matcher(masked).replaceAll("$1****$3");
        masked = DOUBLE_QUOTED_PASSWORD.matcher(masked).replaceAll("$1****$3");
        return masked;
    }
}
