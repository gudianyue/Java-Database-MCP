package dev.databasemcp.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseDialectProviderTest {

    @Test
    void currentSelectsMysqlDialectWhenPropertiesUseMysql() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.MYSQL);
        DatabaseDialect mysqlDialect = new StubDatabaseDialect(DatabaseType.MYSQL);
        DatabaseDialect postgresqlDialect = new StubDatabaseDialect(DatabaseType.POSTGRESQL);

        DatabaseDialectProvider provider = new DatabaseDialectProvider(
            properties,
            List.of(postgresqlDialect, mysqlDialect)
        );

        assertThat(provider.current()).isSameAs(mysqlDialect);
    }

    @Test
    void currentSelectsDamengDialectWhenPropertiesUseDameng() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.DAMENG);
        DatabaseDialect damengDialect = new StubDatabaseDialect(DatabaseType.DAMENG);
        DatabaseDialect postgresqlDialect = new StubDatabaseDialect(DatabaseType.POSTGRESQL);

        DatabaseDialectProvider provider = new DatabaseDialectProvider(
            properties,
            List.of(postgresqlDialect, damengDialect)
        );

        assertThat(provider.current()).isSameAs(damengDialect);
    }

    @Test
    void currentSelectsDorisDialectWhenPropertiesUseDoris() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.DORIS);
        DatabaseDialect dorisDialect = new StubDatabaseDialect(DatabaseType.DORIS);
        DatabaseDialect mysqlDialect = new StubDatabaseDialect(DatabaseType.MYSQL);
        DatabaseDialect damengDialect = new StubDatabaseDialect(DatabaseType.DAMENG);

        DatabaseDialectProvider provider = new DatabaseDialectProvider(
            properties,
            List.of(mysqlDialect, dorisDialect, damengDialect)
        );

        assertThat(provider.current()).isSameAs(dorisDialect);
    }

    @Test
    void currentThrowsWhenNoDialectMatchesProperties() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.MYSQL);
        DatabaseDialect postgresqlDialect = new StubDatabaseDialect(DatabaseType.POSTGRESQL);

        DatabaseDialectProvider provider = new DatabaseDialectProvider(properties, List.of(postgresqlDialect));

        assertThatThrownBy(provider::current)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("没有找到数据库方言");
    }

    private record StubDatabaseDialect(DatabaseType databaseType) implements DatabaseDialect {

        @Override
        public QueryResult listSchemas() {
            return QueryResult.empty();
        }

        @Override
        public QueryResult listObjects(String schemaName, String objectType) {
            return QueryResult.empty();
        }

        @Override
        public QueryResult getObjectDetails(String schemaName, String objectName, String objectType) {
            return QueryResult.empty();
        }

        @Override
        public QueryResult explain(String sql) {
            return QueryResult.empty();
        }
    }
}
