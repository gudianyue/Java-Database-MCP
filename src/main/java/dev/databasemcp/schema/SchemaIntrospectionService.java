package dev.databasemcp.schema;

import dev.databasemcp.dialect.DatabaseDialectProvider;
import dev.databasemcp.sql.QueryResult;
import org.springframework.stereotype.Service;

@Service
public class SchemaIntrospectionService {

    private final DatabaseDialectProvider dialectProvider;

    public SchemaIntrospectionService(DatabaseDialectProvider dialectProvider) {
        this.dialectProvider = dialectProvider;
    }

    public QueryResult listSchemas() {
        return dialectProvider.current().listSchemas();
    }

    public QueryResult listObjects(String schemaName, String objectType) {
        return dialectProvider.current().listObjects(schemaName, objectType == null ? "table" : objectType);
    }

    public QueryResult getObjectDetails(String schemaName, String objectName, String objectType) {
        return dialectProvider.current()
            .getObjectDetails(schemaName, objectName, objectType == null ? "table" : objectType);
    }
}
