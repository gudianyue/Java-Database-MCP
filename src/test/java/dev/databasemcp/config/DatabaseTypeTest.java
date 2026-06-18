package dev.databasemcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseTypeTest {

    @Test
    void parsesSupportedDatabaseTypesCaseInsensitively() {
        assertThat(DatabaseType.from("postgresql")).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(DatabaseType.from("mysql")).isEqualTo(DatabaseType.MYSQL);
        assertThat(DatabaseType.from("dameng")).isEqualTo(DatabaseType.DAMENG);
        assertThat(DatabaseType.from("POSTGRESQL")).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(DatabaseType.from("DAMENG")).isEqualTo(DatabaseType.DAMENG);
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
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotTreatDamengAliasesAsCanonicalDatabaseTypes() {
        assertThatThrownBy(() -> DatabaseType.from("dm"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseType.from("dm8"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
