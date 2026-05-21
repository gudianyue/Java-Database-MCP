# Java MCP 兼容性说明

本项目会重设计 Python `postgres-mcp` 的内部结构，同时保留用户可见的 MCP 工具意图。

## 已实现工具名

- `list_schemas`
- `list_objects`
- `get_object_details`
- `execute_sql`
- `explain_query`
- `get_top_queries`

## 已知细微差异

- 阶段 A 的 Java 工具通过 Spring AI/MCP 注解返回文本化行结果。Java SDK 生成的精确 JSON schema 可能和 Python FastMCP 生成结果略有不同。
- `get_object_details` 使用 PostgreSQL JSON 聚合在一条查询中组合表/视图详情，而不是像 Python 版本那样在应用侧组合多次查询结果。
- 受限 SQL 校验先采用保守的 Java allowlist 和数据库只读事务保护。在选定 Java PostgreSQL 解析器之前，它会有意比 Python `pglast` AST 遍历更窄。
- `explain_query` 当前返回 PostgreSQL `EXPLAIN (FORMAT TEXT)` 的文本计划；Python 版本会把 JSON 计划转换为更丰富的展示对象。
- `explain_query` 的假设索引使用 `hypopg_create_index(?)` 参数化传入索引定义，并对表名、列名和索引方法做 Java 侧校验。
- `explain_query` 在 `analyze=true` 时拒绝执行以 `INSERT`、`UPDATE`、`DELETE`、`MERGE`、`COPY`、`CALL` 开头的目标 SQL，避免通过执行计划工具触发写入语句。
- `get_top_queries` 保留 `resources`、`mean_time`、`total_time` 三种排序入口，并兼容 PostgreSQL 12 与 13+ 的 `pg_stat_statements` 时间列差异。

## 兼容性规则

只有在保留工具意图、已记录在本文档、并且有测试覆盖时，细微差异才可以接受。
