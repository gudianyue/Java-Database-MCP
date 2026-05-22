package dev.databasemcp.dialect;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;

public interface DatabaseDialect {
    DatabaseType databaseType();

    QueryResult listSchemas();

    QueryResult listObjects(String schemaName, String objectType);

    QueryResult getObjectDetails(String schemaName, String objectName, String objectType);

    QueryResult explain(String sql);
}
