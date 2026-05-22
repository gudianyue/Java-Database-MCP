package dev.databasemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.databasemcp.sql.QueryResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolResponseMapperTest {

    private final ToolResponseMapper mapper = new ToolResponseMapper();

    @Test
    void mapsEmptyRowsToEmptyArrayText() {
        assertThat(mapper.toText(QueryResult.empty())).isEqualTo("[]");
    }

    @Test
    void mapsRowsToText() {
        assertThat(mapper.toText(new QueryResult(List.of(Map.of("schema_name", "public")))))
            .contains("schema_name")
            .contains("public");
    }

    @Test
    void masksSecretInErrors() {
        assertThat(mapper.error(new IllegalStateException("postgresql://user:secret@localhost/db")))
            .contains("错误：")
            .contains("****")
            .doesNotContain("secret");
    }
}
