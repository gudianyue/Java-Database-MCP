# 通用数据库 MCP 兼容性说明

本文记录 Database MCP Java 在通用数据库支持过程中的兼容性边界。项目保留原有 PostgreSQL 诊断工具意图，同时扩展到 MySQL 的基础结构查看、受控 SQL 执行和诊断工具。

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

## 架构设计

诊断工具通过 `DiagnosticDialect` 接口多态路由到引擎特定实现：
- `PostgresDiagnosticDialect`：从原 TopQueriesService、DatabaseHealthService、IndexAdvisorService 中提取的 PG 逻辑
- `MySqlDiagnosticDialect`：使用 performance_schema、information_schema、InnoDB 状态变量的 MySQL 逻辑
- `DiagnosticDialectProvider`：根据配置的 `DatabaseType` 选择对应方言，与 `DatabaseDialectProvider` 模式一致
- 原三个 Service（TopQueriesService、DatabaseHealthService、IndexAdvisorService）变为薄路由委托层

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

## MySQL 诊断工具行为

### `get_top_queries`

- 数据源：`performance_schema.events_statements_summary_by_digest`
- 前置条件：`performance_schema` 必须启用（MySQL 5.6.6+ 默认开启，部分云 MySQL 可能关闭）
- 排序方式：`mean_time`（单次平均执行时间）、`total_time`（总执行时间）
- 不支持 `resources` 排序（MySQL 无 WAL/shared_blks 等概念），默认排序为 `total_time`
- MySQL `DIGEST_TEXT` 会将常量替换为 `?`，实现查询自动归一化

### `analyze_db_health`

- 健康检查类型映射（对比 PG）：

| PG 类型 | MySQL 类型 | 说明 |
|---|---|---|
| `index` | `index` | 重复索引、大索引、低使用索引 |
| `connection` | `connection` | 总连接数、长时间 Sleep 连接 |
| `vacuum` | `fragmentation` | 表碎片检查（DATA_FREE 空闲空间） |
| `sequence` | `auto_increment` | AUTO_INCREMENT 列耗尽检查 |
| `replication` | `replication` | 主从状态、复制延迟 |
| `buffer` | `buffer` | InnoDB 缓冲池命中率 |
| `constraint` | `constraint` | 外键约束（MySQL 不支持未验证约束概念） |

- MySQL 有效类型：`index`、`connection`、`fragmentation`、`auto_increment`、`replication`、`buffer`、`constraint`、`all`
- 索引健康检查：重复索引（STATISTICS 表交叉比对）、大索引（innodb_index_stats）、低使用索引（需 performance_schema）
- 低使用索引检查依赖 `performance_schema`；未启用时该子项返回提示信息，其他子项仍正常执行
- 碎片检查：报告 DATA_FREE > 100MB 的 InnoDB 表，并建议 OPTIMIZE TABLE
- 自增列检查：检查 AUTO_INCREMENT 值是否接近对应整数类型的最大值（剩余空间不足 10% 时报告风险）
- 复制检查：MySQL 8.0.22+ 使用 `SHOW REPLICA STATUS`，5.x 使用 `SHOW SLAVE STATUS`
- 缓冲池检查：从 `performance_schema.global_status` 读取 `Innodb_buffer_pool_reads` 和 `Innodb_buffer_pool_read_requests`，计算命中率（阈值 95%）
- 约束检查：列出所有外键约束；MySQL 不支持 PG 的"未验证约束"概念

### `analyze_workload_indexes` 和 `analyze_query_indexes`

- 数据源：`performance_schema.events_statements_summary_by_digest`（workload 模式）
- EXPLAIN 解析：使用 `EXPLAIN FORMAT=JSON`，解析 `query_block` 中的 `access_type`、`table_name`、`attached_condition`
- 索引建议方法：规则引擎评分（`method='dta'`），MySQL 无 HypoPG 等假设索引扩展
- 规则评分逻辑：全表扫描查询权重 × 索引大小衰减因子，按评分排序输出推荐
- 不支持 `hypothetical_indexes`（MySQL 分支返回明确提示）
- `method='llm'` 保留入口，返回暂未接入说明

### `explain_query`

- MySQL 使用 `EXPLAIN FORMAT=JSON`（原为纯文本 `EXPLAIN`，现已升级为 JSON 格式）
- `analyze=true` 不支持（MySQL 的 EXPLAIN ANALYZE 会实际执行写操作，风险过高）
- `hypothetical_indexes` 不支持（无 HypoPG 等价扩展）

### 前置条件检查

- `MySqlFeatureService` 负责 MySQL 特性检查：
  - `isPerformanceSchemaEnabled()`：检查 `@@performance_schema` 是否开启
  - `hasSysSchema()`：检查 `sys` 辅助 schema 是否存在（5.7.7+/8.0+）
  - `mysqlMajorVersion()`：从 `VERSION()` 解析主版本号
  - 当前置条件不满足时返回安装/启用指南，不抛出异常

## 兼容性规则

只有在保留工具意图、已记录在本文档、并且有测试覆盖时，细微差异才可以接受。新增数据库方言时，应优先提供基础结构查看、受控 SQL 执行和基础执行计划，再逐步补充该数据库原生支持的诊断能力。诊断逻辑通过 `DiagnosticDialect` 接口解耦，Service 层仅负责路由委托。