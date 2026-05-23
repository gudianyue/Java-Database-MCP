# 通用数据库 MCP 兼容性说明

本文记录 Database MCP Java 在通用数据库支持过程中的兼容性边界。项目保留原有 PostgreSQL 诊断工具意图，同时扩展到 MySQL 的基础结构查看和受控 SQL 执行。

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

## MySQL 首版范围

MySQL 当前支持基础结构查看和受控 SQL 执行：

- `execute_sql`
- `list_schemas`
- `list_objects`
- `get_object_details`
- 基础 `explain_query`

依赖 PostgreSQL 系统视图、扩展或 HypoPG 的诊断工具在 MySQL 下返回不支持说明，包括慢查询统计、健康检查和索引建议相关工具。

## PostgreSQL 专属行为

- `get_object_details` 使用 PostgreSQL JSON 聚合在单次查询中组合表和视图详情。
- 受限 SQL 校验先采用保守的 Java allowlist 和数据库只读事务保护。
- `explain_query` 返回 PostgreSQL `EXPLAIN (FORMAT TEXT)` 的文本计划。
- `explain_query` 的假设索引能力使用 `hypopg_create_index(?)` 参数化传入索引定义，并在 Java 侧校验表名、列名和索引方法。
- `explain_query` 在 `analyze=true` 时拒绝执行以 `INSERT`、`UPDATE`、`DELETE`、`MERGE`、`COPY`、`CALL` 开头的目标 SQL，避免通过执行计划工具触发写入语句。
- `get_top_queries` 保留 `resources`、`mean_time`、`total_time` 三种排序入口，并兼容 PostgreSQL 12 与 13+ 的 `pg_stat_statements` 时间列差异。
- `analyze_db_health` 保留 `index`、`connection`、`vacuum`、`sequence`、`replication`、`buffer`、`constraint`、`all` 类型和逗号组合输入。
- `analyze_db_health` 当前以 Java 服务内的只读 SQL 检查实现主要健康信号；索引膨胀检查先报告超过 100MB 的大索引。
- `analyze_db_health` 的单项检查如果遇到权限或版本问题，会在该检查项中返回检查失败说明，其他已请求检查仍继续执行。
- `analyze_workload_indexes` 和 `analyze_query_indexes` 保留 `max_index_size_mb` 与 `method` 参数；当前 Java 版本实现 `method='dta'`，`method='llm'` 保留入口并返回暂未接入说明。
- Java DTA 使用 PostgreSQL `EXPLAIN (FORMAT JSON)`、`pg_catalog`、`information_schema` 和 HypoPG 评估候选索引。
- Java DTA 先推荐单列 btree 索引，过滤已有简单索引、长文本、JSON、bytea 列，并按 HypoPG 评估后的成本改善排序。

## 兼容性规则

只有在保留工具意图、已记录在本文档、并且有测试覆盖时，细微差异才可以接受。新增数据库方言时，应优先提供基础结构查看、受控 SQL 执行和基础执行计划，再逐步补充该数据库原生支持的诊断能力。
