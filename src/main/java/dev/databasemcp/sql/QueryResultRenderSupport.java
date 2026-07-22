package dev.databasemcp.sql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** QueryResult 行值渲染与解析的纯静态工具集。 */
public final class QueryResultRenderSupport {

    private QueryResultRenderSupport() {
    }

    public static String joinRows(List<Map<String, Object>> rows, Function<Map<String, Object>, String> formatter) {
        return String.join(System.lineSeparator(), rows.stream().map(formatter).toList());
    }

    public static Object firstValue(QueryResult result, String column) {
        if (result.rows().isEmpty()) {
            return null;
        }
        return result.rows().getFirst().get(column);
    }

    public static long singleLong(QueryResult result, String column) {
        return asLong(firstValue(result, column));
    }

    public static long singleLongFromRow(Map<String, Object> row, String column) {
        return asLong(row.get(column));
    }

    public static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number numeric) {
            return BigDecimal.valueOf(numeric.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    public static String megabytes(Object bytes) {
        return number(bytes).divide(BigDecimal.valueOf(1024L * 1024L), 1, RoundingMode.HALF_UP).toPlainString();
    }

    public static String qualified(Map<String, Object> row, String schemaKey, String nameKey) {
        return row.get(schemaKey) + "." + row.get(nameKey);
    }

    public static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value)
            || "true".equalsIgnoreCase(String.valueOf(value))
            || "1".equals(String.valueOf(value))
            || (value instanceof Number number && number.longValue() == 1L);
    }

    public static boolean isSelect(String sql) {
        return sql != null && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select");
    }

    public static String round(double value) {
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return String.valueOf(value);
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
