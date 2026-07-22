package dev.databasemcp.sql;

import java.util.List;

/** SQL 执行接口，返回 QueryResult。 */
public interface SqlClient {

    QueryResult query(String sql);

    QueryResult query(String sql, List<?> params);

    default QueryResult query(String sql, List<?> params, int timeoutSeconds) {
        throw new UnsupportedOperationException("This SqlClient does not support explicit query timeouts");
    }
}
