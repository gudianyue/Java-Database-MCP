package dev.databasemcp.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.SqlClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticDialectProviderTest {

    @Test
    void selectsPostgresDialectWhenConfigured() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.POSTGRESQL);
        PostgresDiagnosticDialect pgDialect = new PostgresDiagnosticDialect(
            new StubSqlClient(), new StubExtensionService()
        );
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(new ArrayList<>(List.of(pgDialect)), properties);

        assertThat(provider.current().databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
    }

    @Test
    void selectsMySqlDialectWhenConfigured() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.MYSQL);
        MySqlDiagnosticDialect mysqlDialect = new MySqlDiagnosticDialect(
            new StubSqlClient(), new MySqlFeatureService(new StubSqlClient())
        );
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(new ArrayList<>(List.of(mysqlDialect)), properties);

        assertThat(provider.current().databaseType()).isEqualTo(DatabaseType.MYSQL);
    }

    @Test
    void selectsDamengDialectWhenConfigured() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.DAMENG);
        DiagnosticDialect damengDialect = new StubDiagnosticDialect(DatabaseType.DAMENG);
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(new ArrayList<>(List.of(damengDialect)), properties);

        assertThat(provider.current().databaseType()).isEqualTo(DatabaseType.DAMENG);
    }

    @Test
    void selectsDorisDialectWhenConfigured() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.DORIS);
        DiagnosticDialect dorisDialect = new StubDiagnosticDialect(DatabaseType.DORIS);
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(new ArrayList<>(List.of(dorisDialect)), properties);

        assertThat(provider.current().databaseType()).isEqualTo(DatabaseType.DORIS);
    }

    @Test
    void throwsWhenNoDialectMatches() {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.MYSQL);
        DiagnosticDialectProvider provider = new DiagnosticDialectProvider(new ArrayList<>(), properties);

        assertThatThrownBy(() -> provider.current())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MYSQL");
    }

    private static final class StubSqlClient implements SqlClient {
        @Override
        public dev.databasemcp.sql.QueryResult query(String sql) {
            return dev.databasemcp.sql.QueryResult.empty();
        }

        @Override
        public dev.databasemcp.sql.QueryResult query(String sql, java.util.List<?> params) {
            return dev.databasemcp.sql.QueryResult.empty();
        }
    }

    private static final class StubExtensionService extends PostgresExtensionService {
        private StubExtensionService() {
            super(new StubSqlClient());
        }

        @Override
        public boolean isExtensionInstalled(String extensionName) {
            return true;
        }

        @Override
        public int postgresMajorVersion() {
            return 16;
        }
    }

    private record StubDiagnosticDialect(DatabaseType databaseType) implements DiagnosticDialect {
        @Override
        public String getTopQueries(String sortBy, int limit) {
            return "";
        }

        @Override
        public String analyzeHealth(String healthType) {
            return "";
        }

        @Override
        public String analyzeWorkloadIndexes(int maxIndexSizeMb, String method) {
            return "";
        }

        @Override
        public String analyzeQueryIndexes(java.util.List<String> queries, int maxIndexSizeMb, String method) {
            return "";
        }
    }
}
