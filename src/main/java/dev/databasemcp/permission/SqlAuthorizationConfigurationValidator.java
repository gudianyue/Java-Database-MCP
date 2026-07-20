package dev.databasemcp.permission;

import dev.databasemcp.config.DatabaseMcpProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class SqlAuthorizationConfigurationValidator implements InitializingBean {

    private final DatabaseMcpProperties properties;
    private final ObjectProvider<SqlAuthorizer> authorizers;

    SqlAuthorizationConfigurationValidator(
        DatabaseMcpProperties properties,
        ObjectProvider<SqlAuthorizer> authorizers
    ) {
        this.properties = properties;
        this.authorizers = authorizers;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.getPermission().isEnabled() && authorizers.stream().count() != 1) {
            throw new IllegalStateException("exactly one SqlAuthorizer must be configured");
        }
    }
}
