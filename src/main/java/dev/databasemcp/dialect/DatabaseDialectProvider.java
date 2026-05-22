package dev.databasemcp.dialect;

import dev.databasemcp.config.DatabaseMcpProperties;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DatabaseDialectProvider {

    private final DatabaseMcpProperties properties;
    private final List<DatabaseDialect> dialects;

    public DatabaseDialectProvider(DatabaseMcpProperties properties, List<DatabaseDialect> dialects) {
        this.properties = properties;
        this.dialects = dialects;
    }

    public DatabaseDialect current() {
        return dialects.stream()
            .filter(dialect -> dialect.databaseType() == properties.getDatabaseType())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("没有找到数据库方言：" + properties.getDatabaseType()));
    }
}
