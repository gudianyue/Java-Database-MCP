package dev.databasemcp.sql;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public final class SecretMasker {

    private static final Pattern URL_PASSWORD =
        Pattern.compile("(postgres(?:ql)?://[^:\\s]+:)([^@\\s]+)(@[^/\\s]+)", Pattern.CASE_INSENSITIVE);
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
        String masked = maskUri(value);
        masked = URL_PASSWORD.matcher(masked).replaceAll("$1****$3");
        masked = PARAM_PASSWORD.matcher(masked).replaceAll("$1****");
        masked = SINGLE_QUOTED_PASSWORD.matcher(masked).replaceAll("$1****$3");
        masked = DOUBLE_QUOTED_PASSWORD.matcher(masked).replaceAll("$1****$3");
        return masked;
    }

    private static String maskUri(String value) {
        try {
            URI uri = new URI(value);
            String userInfo = uri.getUserInfo();
            if (userInfo == null || !userInfo.contains(":")) {
                return value;
            }
            String user = userInfo.substring(0, userInfo.indexOf(':'));
            URI masked = new URI(uri.getScheme(), user + ":****", uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
            return masked.toString();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return value;
        }
    }
}
