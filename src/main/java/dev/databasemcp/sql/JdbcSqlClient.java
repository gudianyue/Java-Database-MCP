package dev.databasemcp.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.databasemcp.config.DatabaseMcpProperties;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JdbcSqlClient implements SqlClient, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSqlClient.class);

    private final DatabaseMcpProperties properties;
    private final RestrictedSqlGuard restrictedSqlGuard;
    private volatile DataSource dataSource;

    @Autowired
    public JdbcSqlClient(DatabaseMcpProperties properties, RestrictedSqlGuard restrictedSqlGuard) {
        this.properties = properties;
        this.restrictedSqlGuard = restrictedSqlGuard;
    }

    JdbcSqlClient(DatabaseMcpProperties properties, RestrictedSqlGuard restrictedSqlGuard, DataSource dataSource) {
        this.properties = properties;
        this.restrictedSqlGuard = restrictedSqlGuard;
        this.dataSource = dataSource;
    }

    @Override
    public QueryResult query(String sql) {
        return query(sql, List.of());
    }

    @Override
    public QueryResult query(String sql, List<?> params) {
        return query(sql, params, null);
    }

    @Override
    public QueryResult query(String sql, List<?> params, int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be greater than zero");
        }
        return query(sql, params, Integer.valueOf(timeoutSeconds));
    }

    private QueryResult query(String sql, List<?> params, Integer timeoutSeconds) {
        if (properties.getAccessMode() == SqlAccessMode.RESTRICTED) {
            restrictedSqlGuard.validate(sql);
        }
        List<?> effectiveParams = params == null ? List.of() : params;
        long startedAt = System.nanoTime();
        LOGGER.info("SQL 执行开始：sql={}, params={}", sql, effectiveParams);
        try (Connection connection = dataSource().getConnection()) {
            boolean readOnly = properties.getAccessMode() == SqlAccessMode.RESTRICTED;
            connection.setReadOnly(readOnly);
            QueryResult result;
            if (effectiveParams.isEmpty()) {
                result = executeStatement(connection, sql, readOnly, timeoutSeconds);
            } else {
                result = executePreparedStatement(connection, sql, effectiveParams, readOnly, timeoutSeconds);
            }
            LOGGER.info("SQL 执行完成：status=success, elapsedMs={}, rowCount={}", elapsedMillis(startedAt), result.rows().size());
            return result;
        } catch (SQLException e) {
            LOGGER.info("SQL 执行完成：status=failure, elapsedMs={}, error={}", elapsedMillis(startedAt), SecretMasker.mask(e.getMessage()));
            throw new IllegalStateException(SecretMasker.mask(e.getMessage()), e);
        } catch (RuntimeException e) {
            LOGGER.info("SQL 执行完成：status=failure, elapsedMs={}, error={}", elapsedMillis(startedAt), SecretMasker.mask(e.getMessage()));
            throw e;
        }
    }

    private QueryResult executeStatement(Connection connection, String sql, boolean readOnly, Integer timeoutSeconds) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            configureStatement(statement, readOnly, timeoutSeconds);
            boolean hasResultSet = statement.execute(sql);
            if (!hasResultSet) {
                return QueryResult.empty();
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return map(resultSet);
            }
        }
    }

    private QueryResult executePreparedStatement(
        Connection connection,
        String sql,
        List<?> params,
        boolean readOnly,
        Integer timeoutSeconds
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            configureStatement(statement, readOnly, timeoutSeconds);
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

    private void configureStatement(Statement statement, boolean readOnly, Integer timeoutSeconds) throws SQLException {
        if (timeoutSeconds != null) {
            statement.setQueryTimeout(timeoutSeconds);
        } else if (readOnly) {
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
        DataSource current = dataSource;
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

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private HikariDataSource createDataSource() {
        String jdbcUrl = properties.resolvedJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("数据库连接未配置。请设置 DATABASE_URI，或设置 DATABASE_HOST、DATABASE_NAME、DATABASE_USERNAME、DATABASE_PASSWORD。");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (properties.getDatabaseUsername() != null && !properties.getDatabaseUsername().isBlank()) {
            config.setUsername(properties.getDatabaseUsername());
        }
        if (properties.getDatabasePassword() != null && !properties.getDatabasePassword().isBlank()) {
            config.setPassword(properties.getDatabasePassword());
        }
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setPoolName("database-mcp");
        return new HikariDataSource(config);
    }

    @Override
    public void close() {
        DataSource current = dataSource;
        if (current instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("关闭数据库连接池失败。", e);
            }
        }
    }
}
