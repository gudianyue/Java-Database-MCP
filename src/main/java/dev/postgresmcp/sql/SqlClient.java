package dev.postgresmcp.sql;

import java.util.List;

public interface SqlClient {

    QueryResult query(String sql);

    QueryResult query(String sql, List<?> params);
}
