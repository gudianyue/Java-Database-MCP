package dev.postgresmcp.sql;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RestrictedSqlGuardTest {

    private final RestrictedSqlGuard guard = new RestrictedSqlGuard();

    @Test
    void allowsReadOnlyStatements() {
        assertThatCode(() -> guard.validate("select * from users")).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("show server_version")).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("explain select * from users")).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("with x as (select 1) select * from x")).doesNotThrowAnyException();
    }

    @Test
    void blocksMutatingStatements() {
        assertThatThrownBy(() -> guard.validate("insert into users values (1)"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INSERT");
        assertThatThrownBy(() -> guard.validate("drop table users"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DROP");
    }

    @Test
    void blocksUnsafeMultiStatementInput() {
        assertThatThrownBy(() -> guard.validate("select 1; delete from users"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("多语句");
    }

    @Test
    void allowsOnlyCreateExtensionFromCreateStatements() {
        assertThatCode(() -> guard.validate("create extension if not exists hypopg")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validate("create table users(id int)"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CREATE EXTENSION");
    }
}
