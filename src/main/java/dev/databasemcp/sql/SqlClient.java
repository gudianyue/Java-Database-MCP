package dev.databasemcp.sql;

import java.util.List;

public interface SqlClient {

    QueryResult query(String sql);

    QueryResult query(String sql, List<?> params);

    default QueryResult query(String sql, List<?> params, int timeoutSeconds) {
        throw new UnsupportedOperationException("This SqlClient does not support explicit query timeouts");
    }
}
