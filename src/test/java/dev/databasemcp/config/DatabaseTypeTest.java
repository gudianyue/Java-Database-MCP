package dev.databasemcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseTypeTest {

    @Test
    void parsesSupportedDatabaseTypesCaseInsensitively() {
        assertThat(DatabaseType.from("postgresql")).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(DatabaseType.from("mysql")).isEqualTo(DatabaseType.MYSQL);
        assertThat(DatabaseType.from("POSTGRESQL")).isEqualTo(DatabaseType.POSTGRESQL);
    }

    @Test
    void defaultsBlankDatabaseTypeToPostgresql() {
        assertThat(DatabaseType.from(null)).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(DatabaseType.from("")).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(DatabaseType.from("   ")).isEqualTo(DatabaseType.POSTGRESQL);
    }

    @Test
    void rejectsUnsupportedDatabaseType() {
        assertThatThrownBy(() -> DatabaseType.from("oracle"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的数据库类型");
    }
}
