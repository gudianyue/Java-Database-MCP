package dev.databasemcp.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.dialect.DatabaseDialect;
import dev.databasemcp.dialect.DatabaseDialectProvider;
import dev.databasemcp.sql.QueryResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaIntrospectionServiceTest {

    @Test
    void serviceDelegatesCallsToCurrentDialect() {
        RecordingDialect dialect = new RecordingDialect();
        SchemaIntrospectionService service = new SchemaIntrospectionService(providerFor(dialect));

        assertThat(service.listSchemas()).isSameAs(dialect.listSchemasResult);

        QueryResult listObjectsResult = service.listObjects("public", "view");
        assertThat(listObjectsResult).isSameAs(dialect.listObjectsResult);
        assertThat(dialect.lastSchemaName).isEqualTo("public");
        assertThat(dialect.lastObjectType).isEqualTo("view");

        QueryResult detailsResult = service.getObjectDetails("public", "orders", "sequence");
        assertThat(detailsResult).isSameAs(dialect.objectDetailsResult);
        assertThat(dialect.lastSchemaName).isEqualTo("public");
        assertThat(dialect.lastObjectName).isEqualTo("orders");
        assertThat(dialect.lastObjectType).isEqualTo("sequence");
    }

    @Test
    void nullObjectTypeDefaultsToTableBeforeDelegating() {
        RecordingDialect dialect = new RecordingDialect();
        SchemaIntrospectionService service = new SchemaIntrospectionService(providerFor(dialect));

        service.listObjects("public", null);
        assertThat(dialect.lastObjectType).isEqualTo("table");

        service.getObjectDetails("public", "orders", null);
        assertThat(dialect.lastObjectType).isEqualTo("table");
    }

    private static DatabaseDialectProvider providerFor(DatabaseDialect dialect) {
        DatabaseMcpProperties properties = new DatabaseMcpProperties();
        properties.setDatabaseType(DatabaseType.POSTGRESQL);
        return new DatabaseDialectProvider(properties, List.of(dialect));
    }

    private static final class RecordingDialect implements DatabaseDialect {
        private final QueryResult listSchemasResult = new QueryResult(List.of(
            Map.of("schema_name", "public")
        ));
        private final QueryResult listObjectsResult = new QueryResult(List.of(
            Map.of("table_name", "orders")
        ));
        private final QueryResult objectDetailsResult = new QueryResult(List.of(
            Map.of("name", "orders")
        ));
        private String lastSchemaName;
        private String lastObjectName;
        private String lastObjectType;

        @Override
        public DatabaseType databaseType() {
            return DatabaseType.POSTGRESQL;
        }

        @Override
        public QueryResult listSchemas() {
            return listSchemasResult;
        }

        @Override
        public QueryResult listObjects(String schemaName, String objectType) {
            this.lastSchemaName = schemaName;
            this.lastObjectType = objectType;
            return listObjectsResult;
        }

        @Override
        public QueryResult getObjectDetails(String schemaName, String objectName, String objectType) {
            this.lastSchemaName = schemaName;
            this.lastObjectName = objectName;
            this.lastObjectType = objectType;
            return objectDetailsResult;
        }

        @Override
        public QueryResult explain(String sql) {
            return QueryResult.empty();
        }
    }
}
