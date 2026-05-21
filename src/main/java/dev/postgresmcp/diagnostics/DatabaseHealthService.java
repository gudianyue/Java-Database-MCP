package dev.postgresmcp.diagnostics;

import dev.postgresmcp.sql.QueryResult;
import dev.postgresmcp.sql.SecretMasker;
import dev.postgresmcp.sql.SqlClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DatabaseHealthService {

    private static final Set<String> VALID_HEALTH_TYPES = Set.of(
        "index",
        "connection",
        "vacuum",
        "sequence",
        "replication",
        "buffer",
        "constraint",
        "all"
    );

    private final SqlClient sqlClient;

    public DatabaseHealthService(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public String analyze(String healthType) {
        LinkedHashSet<String> healthTypes = parseHealthTypes(healthType);
        if (healthTypes.contains("all")) {
            healthTypes = new LinkedHashSet<>(List.of("index", "connection", "vacuum", "sequence", "replication", "buffer", "constraint"));
        }

        List<String> sections = new ArrayList<>();
        for (String type : healthTypes) {
            switch (type) {
                case "index" -> sections.add(runCheck("索引健康", this::indexHealth));
                case "connection" -> sections.add(runCheck("连接健康", this::connectionHealth));
                case "vacuum" -> sections.add(runCheck("Vacuum 健康", this::vacuumHealth));
                case "sequence" -> sections.add(runCheck("序列健康", this::sequenceHealth));
                case "replication" -> sections.add(runCheck("复制健康", this::replicationHealth));
                case "buffer" -> sections.add(runCheck("缓冲区健康", this::bufferHealth));
                case "constraint" -> sections.add(runCheck("约束健康", this::constraintHealth));
                default -> throw new IllegalArgumentException("不支持的健康检查类型：" + type);
            }
        }
        return sections.isEmpty() ? "未执行任何健康检查。" : String.join(System.lineSeparator(), sections);
    }

    private static LinkedHashSet<String> parseHealthTypes(String healthType) {
        String raw = healthType == null || healthType.isBlank() ? "all" : healthType;
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
            .map(String::trim)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .forEach(parsed::add);
        for (String type : parsed) {
            if (!VALID_HEALTH_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                    "无效健康检查类型：'" + healthType + "'。有效值为：" + String.join(", ", VALID_HEALTH_TYPES)
                );
            }
        }
        return parsed;
    }

    private String indexHealth() {
        return String.join(System.lineSeparator(),
            "无效索引检查：" + invalidIndexes(),
            "重复索引检查：" + duplicateIndexes(),
            "索引膨胀检查：" + largeIndexes(),
            "低使用索引检查：" + unusedIndexes()
        );
    }

    private String invalidIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                n.nspname AS schema,
                ix.relname AS index,
                t.relname AS table
            FROM pg_index i
            JOIN pg_class ix ON ix.oid = i.indexrelid
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = ix.relnamespace
            WHERE NOT i.indisvalid
            ORDER BY 1, 2
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现无效索引。";
        }
        return joinRows(result.rows(), row -> "索引 " + qualified(row, "schema", "index") + " 在表 " + row.get("table") + " 上无效。");
    }

    private String duplicateIndexes() {
        QueryResult result = sqlClient.query(
            """
            WITH index_defs AS (
                SELECT
                    n.nspname AS schema,
                    t.relname AS table,
                    ix.relname AS index,
                    regexp_replace(pg_get_indexdef(i.indexrelid), '^CREATE (UNIQUE )?INDEX [^ ]+ ON ', 'CREATE INDEX ON ') AS normalized_definition,
                    i.indisprimary,
                    i.indisunique
                FROM pg_index i
                JOIN pg_class ix ON ix.oid = i.indexrelid
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = ix.relnamespace
                WHERE i.indisvalid
            )
            SELECT
                a.schema,
                a.table,
                a.index AS duplicate_index,
                b.index AS covering_index
            FROM index_defs a
            JOIN index_defs b
                ON a.schema = b.schema
                AND a.table = b.table
                AND a.normalized_definition = b.normalized_definition
                AND a.index > b.index
            WHERE NOT a.indisprimary
                AND NOT a.indisunique
            ORDER BY 1, 2, 3
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现重复索引。";
        }
        return joinRows(result.rows(), row ->
            "索引 " + row.get("duplicate_index") + " 与 " + row.get("covering_index") + " 在表 " + row.get("table") + " 上重复。"
        );
    }

    private String largeIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                schemaname AS schema,
                relname AS table,
                indexrelname AS index,
                pg_relation_size(indexrelid) AS size_bytes
            FROM pg_stat_user_indexes
            WHERE pg_relation_size(indexrelid) >= ?
            ORDER BY pg_relation_size(indexrelid) DESC
            """,
            List.of(104_857_600)
        );
        if (result.rows().isEmpty()) {
            return "未发现超过 100MB 的大索引。";
        }
        return joinRows(result.rows(), row ->
            "索引 " + row.get("index") + " 位于表 " + row.get("table") + "，大小约 " + megabytes(row.get("size_bytes")) + "MB。"
        );
    }

    private String unusedIndexes() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                schemaname AS schema,
                relname AS table,
                indexrelname AS index,
                idx_scan AS index_scans,
                pg_relation_size(i.indexrelid) AS size_bytes,
                indisprimary AS primary
            FROM pg_stat_user_indexes ui
            JOIN pg_index i ON ui.indexrelid = i.indexrelid
            WHERE NOT indisunique
                AND idx_scan <= ?
            ORDER BY pg_relation_size(i.indexrelid) DESC, relname ASC
            """,
            List.of(50)
        );
        List<Map<String, Object>> rows = result.rows().stream()
            .filter(row -> !truthy(row.get("primary")))
            .toList();
        if (rows.isEmpty()) {
            return "未发现低使用索引。";
        }
        return joinRows(rows, row ->
            "索引 " + row.get("index") + " 位于表 " + row.get("table") + "，扫描次数 "
                + row.get("index_scans") + "，大小约 " + megabytes(row.get("size_bytes")) + "MB。"
        );
    }

    private String connectionHealth() {
        long total = singleLong(sqlClient.query("SELECT COUNT(*) AS count FROM pg_stat_activity"), "count");
        long idleInTransaction = singleLong(
            sqlClient.query("SELECT COUNT(*) AS count FROM pg_stat_activity WHERE state = 'idle in transaction'"),
            "count"
        );
        if (total > 500) {
            return "连接数过高：" + total + "。";
        }
        if (idleInTransaction > 100) {
            return "idle in transaction 连接数过高：" + idleInTransaction + "。";
        }
        return "连接健康：" + total + " 个总连接，" + idleInTransaction + " 个 idle in transaction。";
    }

    private String vacuumHealth() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                n.nspname AS schema,
                c.relname AS table,
                2146483648 - GREATEST(AGE(c.relfrozenxid), AGE(t.relfrozenxid)) AS transactions_left
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_class t ON c.reltoastrelid = t.oid
            WHERE c.relkind = 'r'
                AND (2146483648 - GREATEST(AGE(c.relfrozenxid), AGE(t.relfrozenxid))) < ?
            ORDER BY 3, 1, 2
            """,
            List.of(10_000_000)
        );
        if (result.rows().isEmpty()) {
            return "未发现接近事务 ID 回卷风险的表。";
        }
        return joinRows(result.rows(), row ->
            "表 " + qualified(row, "schema", "table") + " 距离事务 ID 回卷约剩余 " + row.get("transactions_left") + " 个事务。"
        );
    }

    private String sequenceHealth() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                sequence_schema AS schema,
                sequence_name AS sequence,
                data_type,
                start_value,
                minimum_value,
                maximum_value
            FROM information_schema.sequences
            WHERE sequence_schema NOT IN ('pg_catalog', 'information_schema')
            ORDER BY 1, 2
            """
        );
        if (result.rows().isEmpty()) {
            return "数据库中未发现序列。";
        }
        return "发现 " + result.rows().size() + " 个序列；请结合业务增长速度检查接近最大值的序列。";
    }

    private String replicationHealth() {
        boolean replica = truthy(firstValue(sqlClient.query("SELECT pg_is_in_recovery() AS replica"), "replica"));
        long activeReplicas = singleLong(sqlClient.query("SELECT COUNT(*) AS count FROM pg_stat_replication"), "count");
        QueryResult slots = sqlClient.query(
            """
            SELECT
                slot_name,
                database,
                active
            FROM pg_replication_slots
            ORDER BY slot_name
            """
        );
        List<String> lines = new ArrayList<>();
        if (replica) {
            lines.add("当前数据库是副本。");
            lines.add(replicationLag());
        } else {
            lines.add("当前数据库是主库。");
            lines.add(activeReplicas > 0 ? "存在活跃副本连接：" + activeReplicas + "。" : "未发现活跃副本连接。");
        }
        if (slots.rows().isEmpty()) {
            lines.add("未发现复制槽。");
        } else {
            lines.add("复制槽数量：" + slots.rows().size() + "。");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String replicationLag() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                CASE
                    WHEN NOT pg_is_in_recovery()
                        OR pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn()
                    THEN 0
                    ELSE EXTRACT(EPOCH FROM NOW() - pg_last_xact_replay_timestamp())
                END AS replication_lag
            """
        );
        Object lag = firstValue(result, "replication_lag");
        if (lag == null) {
            return "无法读取复制延迟。";
        }
        return "复制延迟：" + lag + " 秒。";
    }

    private String bufferHealth() {
        String indexRate = cacheHitRate(
            sqlClient.query(
                """
                SELECT
                    sum(idx_blks_hit)::numeric / nullif(sum(idx_blks_hit + idx_blks_read), 0) AS rate
                FROM pg_statio_user_indexes
                """
            ),
            "索引缓存"
        );
        String tableRate = cacheHitRate(
            sqlClient.query(
                """
                SELECT
                    sum(heap_blks_hit)::numeric / nullif(sum(heap_blks_hit + heap_blks_read), 0) AS rate
                FROM pg_statio_user_tables
                """
            ),
            "表缓存"
        );
        return indexRate + System.lineSeparator() + tableRate;
    }

    private String cacheHitRate(QueryResult result, String label) {
        Object value = firstValue(result, "rate");
        if (value == null) {
            return label + "暂无统计数据。";
        }
        BigDecimal percent = number(value).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        String relation = percent.compareTo(BigDecimal.valueOf(95)) >= 0 ? "高于" : "低于";
        return label + "命中率：" + percent + "%，" + relation + " 95.0% 阈值。";
    }

    private String constraintHealth() {
        QueryResult result = sqlClient.query(
            """
            SELECT
                nsp.nspname AS schema,
                rel.relname AS table,
                con.conname AS name,
                fnsp.nspname AS referenced_schema,
                frel.relname AS referenced_table
            FROM pg_constraint con
            JOIN pg_class rel ON rel.oid = con.conrelid
            LEFT JOIN pg_class frel ON frel.oid = con.confrelid
            LEFT JOIN pg_namespace nsp ON nsp.oid = con.connamespace
            LEFT JOIN pg_namespace fnsp ON fnsp.oid = frel.relnamespace
            WHERE con.convalidated = false
            ORDER BY 1, 2, 3
            """
        );
        if (result.rows().isEmpty()) {
            return "未发现无效约束。";
        }
        return joinRows(result.rows(), row ->
            "约束 " + row.get("name") + " 位于表 " + qualified(row, "schema", "table") + "，当前未验证。"
        );
    }

    private String runCheck(String title, HealthCheck check) {
        try {
            return title + "：" + System.lineSeparator() + check.run();
        } catch (Exception e) {
            return title + "：" + System.lineSeparator() + "检查失败：" + SecretMasker.mask(e.getMessage());
        }
    }

    private static String joinRows(List<Map<String, Object>> rows, RowFormatter formatter) {
        return String.join(System.lineSeparator(), rows.stream().map(formatter::format).toList());
    }

    private static Object firstValue(QueryResult result, String column) {
        if (result.rows().isEmpty()) {
            return null;
        }
        return result.rows().getFirst().get(column);
    }

    private static long singleLong(QueryResult result, String column) {
        Object value = firstValue(result, column);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number numeric) {
            return BigDecimal.valueOf(numeric.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static String megabytes(Object bytes) {
        return number(bytes).divide(BigDecimal.valueOf(1024 * 1024), 1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String qualified(Map<String, Object> row, String schemaKey, String nameKey) {
        return row.get(schemaKey) + "." + row.get(nameKey);
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    @FunctionalInterface
    private interface HealthCheck {
        String run();
    }

    @FunctionalInterface
    private interface RowFormatter {
        String format(Map<String, Object> row);
    }
}
