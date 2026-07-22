package dev.databasemcp.dialect;

import dev.databasemcp.config.DatabaseType;
import dev.databasemcp.sql.QueryResult;

/** 数据库结构方言接口，负责 schema 查看、对象列举、对象详情与基础 EXPLAIN。 */
public interface DatabaseDialect {
    DatabaseType databaseType();

    QueryResult listSchemas();

    QueryResult listObjects(String schemaName, String objectType);

    QueryResult getObjectDetails(String schemaName, String objectName, String objectType);

    QueryResult explain(String sql);
}
