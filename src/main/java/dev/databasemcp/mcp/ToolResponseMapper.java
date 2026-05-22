package dev.databasemcp.mcp;

import dev.databasemcp.sql.QueryResult;
import dev.databasemcp.sql.SecretMasker;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolResponseMapper {

    public String toText(QueryResult queryResult) {
        List<Map<String, Object>> rows = queryResult.rows();
        if (rows == null || rows.isEmpty()) {
            return "[]";
        }
        return rows.toString();
    }

    public String error(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return "错误：" + SecretMasker.mask(message);
    }
}
