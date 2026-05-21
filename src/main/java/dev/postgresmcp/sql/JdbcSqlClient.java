package dev.postgresmcp.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.postgresmcp.config.PostgresMcpProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class JdbcSqlClient implements SqlClient, AutoCloseable {

    private final PostgresMcpProperties properties;
    private final RestrictedSqlGuard restrictedSqlGuard;
    private volatile HikariDataSource dataSource;

    public JdbcSqlClient(PostgresMcpProperties properties, RestrictedSqlGuard restrictedSqlGuard) {
        this.properties = properties;
        this.restrictedSqlGuard = restrictedSqlGuard;
    }

    @Override
    public QueryResult query(String sql) {
        return query(sql, List.of());
    }

    @Override
    public QueryResult query(String sql, List<?> params) {
        if (properties.getAccessMode() == SqlAccessMode.RESTRICTED) {
            restrictedSqlGuard.validate(sql);
        }
        try (Connection connection = dataSource().getConnection()) {
            boolean readOnly = properties.getAccessMode() == SqlAccessMode.RESTRICTED;
            connection.setReadOnly(readOnly);
            if (params == null || params.isEmpty()) {
                return executeStatement(connection, sql, readOnly);
            }
            return executePreparedStatement(connection, sql, params, readOnly);
        } catch (SQLException e) {
            throw new IllegalStateException(SecretMasker.mask(e.getMessage()), e);
        }
    }

    private QueryResult executeStatement(Connection connection, String sql, boolean readOnly) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            configureStatement(statement, readOnly);
            boolean hasResultSet = statement.execute(sql);
            if (!hasResultSet) {
                return QueryResult.empty();
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return map(resultSet);
            }
        }
    }

    private QueryResult executePreparedStatement(Connection connection, String sql, List<?> params, boolean readOnly) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            configureStatement(statement, readOnly);
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            boolean hasResultSet = statement.execute();
            if (!hasResultSet) {
                return QueryResult.empty();
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return map(resultSet);
            }
        }
    }

    private void configureStatement(Statement statement, boolean readOnly) throws SQLException {
        if (readOnly) {
            statement.setQueryTimeout(properties.getRestrictedTimeoutSeconds());
        }
    }

    private QueryResult map(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
            }
            rows.add(row);
        }
        return new QueryResult(rows);
    }

    private DataSource dataSource() {
        HikariDataSource current = dataSource;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (dataSource == null) {
                dataSource = createDataSource();
            }
            return dataSource;
        }
    }

    private HikariDataSource createDataSource() {
        String databaseUri = properties.getDatabaseUri();
        if (databaseUri == null || databaseUri.isBlank()) {
            throw new IllegalStateException("数据库连接地址未配置。请设置 DATABASE_URI 或 postgres-mcp.database-uri。");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(databaseUri);
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setPoolName("postgres-mcp");
        return new HikariDataSource(config);
    }

    @Override
    public void close() {
        HikariDataSource current = dataSource;
        if (current != null) {
            current.close();
        }
    }
}
