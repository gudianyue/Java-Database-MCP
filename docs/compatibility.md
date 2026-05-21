# Java MCP 兼容性说明

本项目会重设计 Python `postgres-mcp` 的内部结构，同时保留用户可见的 MCP 工具意图。

## 已实现工具名

- `list_schemas`
- `list_objects`
- `get_object_details`
- `execute_sql`
- `explain_query`
- `get_top_queries`
- `analyze_db_health`
- `analyze_workload_indexes`
- `analyze_query_indexes`

## 已知细微差异

- 阶段 A 的 Java 工具通过 Spring AI/MCP 注解返回文本化行结果。Java SDK 生成的精确 JSON schema 可能和 Python FastMCP 生成结果略有不同。
- `get_object_details` 使用 PostgreSQL JSON 聚合在一条查询中组合表/视图详情，而不是像 Python 版本那样在应用侧组合多次查询结果。
- 受限 SQL 校验先采用保守的 Java allowlist 和数据库只读事务保护。在选定 Java PostgreSQL 解析器之前，它会有意比 Python `pglast` AST 遍历更窄。
- `explain_query` 当前返回 PostgreSQL `EXPLAIN (FORMAT TEXT)` 的文本计划；Python 版本会把 JSON 计划转换为更丰富的展示对象。
- `explain_query` 的假设索引使用 `hypopg_create_index(?)` 参数化传入索引定义，并对表名、列名和索引方法做 Java 侧校验。
- `explain_query` 在 `analyze=true` 时拒绝执行以 `INSERT`、`UPDATE`、`DELETE`、`MERGE`、`COPY`、`CALL` 开头的目标 SQL，避免通过执行计划工具触发写入语句。
- `get_top_queries` 保留 `resources`、`mean_time`、`total_time` 三种排序入口，并兼容 PostgreSQL 12 与 13+ 的 `pg_stat_statements` 时间列差异。
- `analyze_db_health` 保留 `index`、`connection`、`vacuum`、`sequence`、`replication`、`buffer`、`constraint`、`all` 类型和逗号组合输入。
- `analyze_db_health` 当前以 Java 服务内的只读 SQL 检查实现主要健康信号；索引膨胀检查先报告超过 100MB 的大索引，而不是完全复刻 Python 版本的 btree 膨胀估算公式。
- `analyze_db_health` 的单项检查如果遇到权限或版本问题，会在该检查项中返回“检查失败”说明，其他已请求检查仍继续执行。
- `analyze_workload_indexes` 和 `analyze_query_indexes` 保留 `max_index_size_mb` 与 `method` 参数；当前 Java 版本实现 `method='dta'`，`method='llm'` 保留入口并返回暂未接入说明。
- Java DTA 使用 PostgreSQL `EXPLAIN (FORMAT JSON)`、`pg_catalog`、`information_schema` 和 HypoPG 评估候选索引，不逐行复刻 Python 版本基于 `pglast` 的候选生成与贪心枚举。
- Java DTA 先推荐单列 btree 索引，过滤已有简单索引、长文本/JSON/bytea 列，并按 HypoPG 评估后的成本改善排序。

## 兼容性规则

只有在保留工具意图、已记录在本文档、并且有测试覆盖时，细微差异才可以接受。
