package dev.databasemcp.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.databasemcp.config.PostgresMcpProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class JdbcSqlClientLoggingTest {

    @Test
    void logsSqlParametersElapsedTimeAndRowCount(CapturedOutput output) throws Exception {
        String sql = "SELECT name FROM users WHERE name = ?";
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("name");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn("Alice");

        JdbcSqlClient client = new JdbcSqlClient(new PostgresMcpProperties(), new RestrictedSqlGuard(), dataSource);

        QueryResult result = client.query(sql, List.of("Alice"));

        assertThat(result.rows()).hasSize(1);
        verify(statement).setObject(1, "Alice");
        assertThat(output).contains("SQL 执行开始：sql=SELECT name FROM users WHERE name = ?, params=[Alice]");
        assertThat(output).contains("SQL 执行完成：status=success");
        assertThat(output).contains("rowCount=1");
    }

    @Test
    void logsFailureStatusAndElapsedTime(CapturedOutput output) throws Exception {
        String sql = "SELECT * FROM users WHERE password = ?";
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("连接失败 password=secret"));
        JdbcSqlClient client = new JdbcSqlClient(new PostgresMcpProperties(), new RestrictedSqlGuard(), dataSource);

        assertThatThrownBy(() -> client.query(sql, List.of("secret")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("password=****");

        assertThat(output).contains("SQL 执行开始：sql=SELECT * FROM users WHERE password = ?, params=[secret]");
        assertThat(output).contains("SQL 执行完成：status=failure");
        assertThat(output).contains("error=连接失败 password=****");
    }
}
