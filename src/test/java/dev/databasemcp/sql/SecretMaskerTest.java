package dev.databasemcp.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretMaskerTest {

    @Test
    void masksPostgresUriPassword() {
        assertThat(SecretMasker.mask("postgresql://user:secret@localhost:5432/db"))
            .contains("****")
            .doesNotContain("secret");
    }

    @Test
    void masksMySqlUriPassword() {
        assertThat(SecretMasker.mask("mysql://user:secret@localhost:3306/db"))
            .contains("****")
            .doesNotContain("secret");
    }

    @Test
    void masksJdbcUriQueryPassword() {
        assertThat(SecretMasker.mask("jdbc:mysql://localhost:3306/db?user=app&password=secret"))
            .contains("password=****")
            .doesNotContain("secret");
    }

    @Test
    void masksDsnPassword() {
        assertThat(SecretMasker.mask("host=localhost password=secret user=postgres"))
            .contains("password=****")
            .doesNotContain("secret");
    }
}
