package dev.databasemcp.permission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionDomainTest {

    @Test
    void parsesKnownDomainsSafely() {
        assertThat(PermissionDomain.fromExternal("metric")).isEqualTo(PermissionDomain.METRIC);
        assertThat(PermissionDomain.fromExternal(" METRIC ")).isEqualTo(PermissionDomain.METRIC);
        assertThat(PermissionDomain.fromExternal("none")).isEqualTo(PermissionDomain.NONE);
    }

    @Test
    void parsesBlankOrUnknownDomainsAsNone() {
        assertThat(PermissionDomain.fromExternal(null)).isEqualTo(PermissionDomain.NONE);
        assertThat(PermissionDomain.fromExternal("")).isEqualTo(PermissionDomain.NONE);
        assertThat(PermissionDomain.fromExternal("admin")).isEqualTo(PermissionDomain.NONE);
    }
}
