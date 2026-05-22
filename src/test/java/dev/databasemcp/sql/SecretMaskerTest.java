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
    void masksDsnPassword() {
        assertThat(SecretMasker.mask("host=localhost password=secret user=postgres"))
            .contains("password=****")
            .doesNotContain("secret");
    }
}
