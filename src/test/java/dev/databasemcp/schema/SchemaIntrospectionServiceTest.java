package dev.databasemcp.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SqlClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaIntrospectionServiceTest {

    @Test
    void listSchemasUsesInformationSchema() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        new SchemaIntrospectionService(sqlClient).listSchemas();

        assertThat(sqlClient.lastSql).contains("information_schema.schemata");
    }

    @Test
    void listObjectsDefaultsThroughSupportedTypes() {
        RecordingSqlClient sqlClient = new RecordingSqlClient();
        SchemaIntrospectionService service = new SchemaIntrospectionService(sqlClient);

        service.listObjects("public", "table");
        assertThat(sqlClient.lastParams).containsExactlyElementsOf(List.of("public", "BASE TABLE"));

        service.listObjects("public", "extension");
        assertThat(sqlClient.lastSql).contains("pg_extension");
    }

    @Test
    void rejectsUnsupportedObjectType() {
        SchemaIntrospectionService service = new SchemaIntrospectionService(new RecordingSqlClient());

        assertThatThrownBy(() -> service.listObjects("public", "function"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的对象类型");
    }

    private static final class RecordingSqlClient implements SqlClient {
        private String lastSql;
        private List<Object> lastParams = new ArrayList<>();

        @Override
        public QueryResult query(String sql) {
            this.lastSql = sql;
            this.lastParams = List.of();
            return QueryResult.empty();
        }

        @Override
        public QueryResult query(String sql, List<?> params) {
            this.lastSql = sql;
            this.lastParams = new ArrayList<>(params);
            return QueryResult.empty();
        }
    }
}
