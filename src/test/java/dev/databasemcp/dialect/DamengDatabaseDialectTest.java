package dev.databasemcp.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class DamengDatabaseDialectTest {

    @Test
    void 数据库类型返回达梦() {
        DamengDatabaseDialect dialect = new DamengDatabaseDialect(new RecordingSqlClient());

        assertThat(dialect.databaseType()).isEqualTo(DatabaseType.DAMENG);
    }

    @Test
    void 使用达梦系统目录查询() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DamengDatabaseDialect dialect = new DamengDatabaseDialect(sqlClient);

        dialect.listSchemas();
        assertThat(sqlClient.lastSql).contains("ALL_USERS");

        dialect.listObjects("APP", "table");
        assertThat(sqlClient.lastSql).contains("ALL_TABLES");
        assertThat(sqlClient.lastParams).containsExactly("APP");

        dialect.listObjects("APP", "view");
        assertThat(sqlClient.lastSql).contains("ALL_VIEWS");
        assertThat(sqlClient.lastParams).containsExactly("APP");

        dialect.listObjects("APP", "sequence");
        assertThat(sqlClient.lastSql).contains("ALL_SEQUENCES");
        assertThat(sqlClient.lastParams).containsExactly("APP");

        dialect.getObjectDetails("APP", "USERS", "table");
        assertThat(sqlClient.lastSql)
            .contains("ALL_TAB_COLUMNS")
            .contains("ALL_CONSTRAINTS")
            .contains("ALL_INDEXES");
        assertThat(sqlClient.lastParams).containsExactly("APP", "USERS", "APP", "USERS", "APP", "USERS");
    }

    @Test
    void 序列详情使用达梦系统目录() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DamengDatabaseDialect dialect = new DamengDatabaseDialect(sqlClient);

        dialect.getObjectDetails("APP", "USER_SEQ", "sequence");

        assertThat(sqlClient.lastSql).contains("ALL_SEQUENCES");
        assertThat(sqlClient.lastParams).containsExactly("APP", "USER_SEQ");
    }

    @Test
    void extension对象不受支持() {
        DamengDatabaseDialect dialect = new DamengDatabaseDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.listObjects("APP", "extension"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("达梦不支持");

        assertThatThrownBy(() -> dialect.getObjectDetails("APP", "postgis", "extension"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("达梦不支持");
    }

    @Test
    void 不支持的对象类型返回清晰异常() {
        DamengDatabaseDialect dialect = new DamengDatabaseDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.listObjects("APP", "function"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的对象类型");

        assertThatThrownBy(() -> dialect.getObjectDetails("APP", "USERS", "function"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的对象类型");
    }

    @Test
    void 执行计划使用达梦Explain前缀() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DamengDatabaseDialect dialect = new DamengDatabaseDialect(sqlClient);

        dialect.explain("SELECT * FROM USERS");

        assertThat(sqlClient.lastSql).isEqualTo("EXPLAIN SELECT * FROM USERS");
    }

    @Test
    void 方言层执行计划拒绝多语句Sql() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DamengDatabaseDialect dialect = new DamengDatabaseDialect(sqlClient);

        assertThatThrownBy(() -> dialect.explain("SELECT * FROM USERS; DROP TABLE USERS"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("拒绝多语句 SQL");
        assertThatThrownBy(() -> dialect.explain("SELECT '--'; DROP TABLE USERS"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("拒绝多语句 SQL");
        assertThat(sqlClient.lastSql).isNull();
    }

    @Test
    void 方言层执行计划拒绝WithCte() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        DamengDatabaseDialect dialect = new DamengDatabaseDialect(sqlClient);

        assertThatThrownBy(() -> dialect.explain("WITH d AS (DELETE FROM USERS) SELECT * FROM USERS"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("仅支持 SELECT 查询");
        assertThat(sqlClient.lastSql).isNull();
    }

    private static final class RecordingSqlClient implements SqlClient {
        private String lastSql;
        private List<Object> lastParams = List.of();

        @Override
        public QueryResult query(String sql) {
            this.lastSql = sql;
            this.lastParams = List.of();
            return QueryResult.empty();
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            this.lastSql = sql;
            this.lastParams = List.copyOf(params);
            return QueryResult.empty();
        }
    }
}
