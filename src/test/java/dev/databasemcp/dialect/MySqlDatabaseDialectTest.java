package dev.databasemcp.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class MySqlDatabaseDialectTest {

    @Test
    void usesMySqlInformationSchemaQueries() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        MySqlDatabaseDialect dialect = new MySqlDatabaseDialect(sqlClient);

        dialect.listSchemas();
        assertThat(sqlClient.lastSql).contains("information_schema.SCHEMATA");

        dialect.listObjects("app", "table");
        assertThat(sqlClient.lastSql).contains("information_schema.TABLES");
        assertThat(sqlClient.lastParams).containsExactly("app", "BASE TABLE");

        dialect.getObjectDetails("app", "users", "table");
        assertThat(sqlClient.lastSql)
            .contains("information_schema.COLUMNS")
            .contains("information_schema.KEY_COLUMN_USAGE")
            .contains("information_schema.STATISTICS");
    }

    @Test
    void databaseTypeReturnsMysql() {
        MySqlDatabaseDialect dialect = new MySqlDatabaseDialect(new RecordingSqlClient());

        assertThat(dialect.databaseType()).isEqualTo(DatabaseType.MYSQL);
    }

    @Test
    void postgresStyleObjectsAreUnsupported() {
        MySqlDatabaseDialect dialect = new MySqlDatabaseDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.listObjects("app", "sequence"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("MySQL 不支持 PostgreSQL 风格的 sequence 对象。");

        assertThatThrownBy(() -> dialect.listObjects("app", "extension"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("MySQL 不支持 PostgreSQL 风格的 extension 对象。");

        assertThatThrownBy(() -> dialect.getObjectDetails("app", "users", "sequence"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("MySQL 不支持 PostgreSQL 风格的 sequence 对象。");

        assertThatThrownBy(() -> dialect.getObjectDetails("app", "users", "extension"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("MySQL 不支持 PostgreSQL 风格的 extension 对象。");
    }

    @Test
    void explainPrefixesSqlWithMysqlExplain() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        MySqlDatabaseDialect dialect = new MySqlDatabaseDialect(sqlClient);

        dialect.explain("SELECT * FROM users");

        assertThat(sqlClient.lastSql).isEqualTo("EXPLAIN FORMAT=JSON SELECT * FROM users");
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
