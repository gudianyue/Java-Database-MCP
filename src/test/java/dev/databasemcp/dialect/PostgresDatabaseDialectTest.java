package dev.databasemcp.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgresDatabaseDialectTest {

    @Test
    void databaseTypeReturnsPostgresql() {
        PostgresDatabaseDialect dialect = new PostgresDatabaseDialect(new RecordingSqlClient());

        assertThat(dialect.databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
    }

    @Test
    void usesPostgresCatalogQueries() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        PostgresDatabaseDialect dialect = new PostgresDatabaseDialect(sqlClient);

        dialect.listSchemas();
        assertThat(sqlClient.lastSql).contains("information_schema.schemata").contains("pg_%");

        dialect.listObjects("public", "extension");
        assertThat(sqlClient.lastSql).contains("pg_extension");

        dialect.getObjectDetails("public", "users", "table");
        assertThat(sqlClient.lastSql).contains("json_build_object").contains("pg_catalog.pg_attribute");
        String compactSql = sqlClient.lastSql.replaceAll("\\s+", " ");
        assertThat(compactSql)
            .contains("json_agg(json_build_object( 'name', con.conname")
            .contains(") ORDER BY con.conname) FILTER (WHERE con.conname IS NOT NULL)")
            .doesNotContain(") FILTER (WHERE con.conname IS NOT NULL))");
    }

    @Test
    void unsupportedObjectTypeThrowsHelpfulException() {
        PostgresDatabaseDialect dialect = new PostgresDatabaseDialect(new RecordingSqlClient());

        assertThatThrownBy(() -> dialect.listObjects("public", "function"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的对象类型");

        assertThatThrownBy(() -> dialect.getObjectDetails("public", "users", "function"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的对象类型");
    }

    @Test
    void explainUsesPostgresTextFormat() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        PostgresDatabaseDialect dialect = new PostgresDatabaseDialect(sqlClient);

        dialect.explain("SELECT * FROM users");

        assertThat(sqlClient.lastSql).isEqualTo("EXPLAIN (FORMAT TEXT) SELECT * FROM users");
    }

    private static final class RecordingSqlClient implements SqlClient {
        private String lastSql;

        @Override
        public QueryResult query(String sql) {
            this.lastSql = sql;
            return QueryResult.empty();
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            this.lastSql = sql;
            return QueryResult.empty();
        }
    }
}
