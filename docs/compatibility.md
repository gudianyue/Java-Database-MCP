# 通用数据库 MCP 兼容性说明

本文记录 Database MCP Java 在通用数据库支持过程中的兼容性边界。项目保留原 PostgreSQL 诊断工具意图，同时扩展到 MySQL 和达梦数据库的基础结构查看、受控 SQL 执行和诊断工具。

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

- `PostgresDiagnosticDialect`：从原 `TopQueriesService`、`DatabaseHealthService`、`IndexAdvisorService` 中提取的 PostgreSQL 逻辑
- `MySqlDiagnosticDialect`：使用 `performance_schema`、`information_schema`、InnoDB 状态变量的 MySQL 逻辑
- `DamengDiagnosticDialect`：使用达梦系统视图和 `EXPLAIN` 的只读诊断逻辑
- `DiagnosticDialectProvider`：根据配置的 `DatabaseType` 选择对应方言，与 `DatabaseDialectProvider` 模式一致
- 原三个 Service：`TopQueriesService`、`DatabaseHealthService`、`IndexAdvisorService` 变为薄路由委托层

## PostgreSQL 专属行为

- `get_object_details` 使用 PostgreSQL JSON 聚合在单次查询中组合表和视图详情。
- 受限 SQL 校验先采用保守的 Java allowlist 和数据库只读事务保护。
- `explain_query` 返回 PostgreSQL `EXPLAIN (FORMAT TEXT)` 的文本计划。
- `explain_query` 的假设索引能力使用 `hypopg_create_index(?)` 参数化传入索引定义，并在 Java 侧校验表名、列名和索引方法。
- `explain_query` 在 `analyze=true` 时拒绝执行以 `INSERT`、`UPDATE`、`DELETE`、`MERGE`、`COPY`、`CALL` 开头的目标 SQL，避免通过执行计划工具触发写入语句。
- `get_top_queries` 保留 `resources`、`mean_time`、`total_time` 三种排序入口，并兼容 PostgreSQL 12 与 13+ 的 `pg_stat_statements` 时间列差异。
- `analyze_db_health` 支持 `index`、`connection`、`vacuum`、`sequence`、`replication`、`buffer`、`constraint`、`all` 类型和逗号组合输入。
- `analyze_db_health` 当前以 Java 服务内的只读 SQL 检查实现主要健康信号；索引膨胀检查先报告超过 100MB 的大索引。
- `analyze_db_health` 的单项检查如果遇到权限或版本问题，会在该检查项中返回失败说明，其它已请求检查仍继续执行。
- `analyze_workload_indexes` 和 `analyze_query_indexes` 保留 `max_index_size_mb` 与 `method` 参数；当前 Java 版本实现 `method='dta'`，`method='llm'` 保留入口并返回暂未接入说明。
- Java DTA 使用 PostgreSQL `EXPLAIN (FORMAT JSON)`、`pg_catalog`、`information_schema` 和 HypoPG 评估候选索引。
- Java DTA 先推荐单列 `btree` 索引，过滤已有简单索引、长文本、JSON、`bytea` 列，并按 HypoPG 评估后的成本改善排序。

## MySQL 诊断工具行为

### `get_top_queries`

- 数据源：`performance_schema.events_statements_summary_by_digest`
- 前置条件：`performance_schema` 必须启用；MySQL 5.6.6+ 默认启用，但部分实例可能关闭
- 排序方式：`mean_time`（单次平均执行时间）、`total_time`（总执行时间）
- 不支持 `resources` 排序，因为 MySQL 没有 PostgreSQL WAL/shared_blks 等等价指标；默认排序为 `total_time`
- MySQL `DIGEST_TEXT` 会将常量替换为 `?`，实现查询自动归一化

### `analyze_db_health`

| PostgreSQL 类型 | MySQL 类型 | 说明 |
|---|---|---|
| `index` | `index` | 重复索引、大索引、低使用索引 |
| `connection` | `connection` | 总连接数、长时间 Sleep 连接 |
| `vacuum` | `fragmentation` | 表碎片检查，基于 DATA_FREE 空闲空间 |
| `sequence` | `auto_increment` | AUTO_INCREMENT 列耗尽检查 |
| `replication` | `replication` | 主从状态、复制延迟 |
| `buffer` | `buffer` | InnoDB 缓冲池命中率 |
| `constraint` | `constraint` | 外键约束；MySQL 不支持未验证约束概念 |

- MySQL 有效类型：`index`、`connection`、`fragmentation`、`auto_increment`、`replication`、`buffer`、`constraint`、`all`
- 索引健康检查包含重复索引、大索引和低使用索引；低使用索引依赖 `performance_schema`，未启用时该子项返回提示信息，其它子项仍正常执行
- 碎片检查报告 DATA_FREE > 100MB 的 InnoDB 表，并建议评估 `OPTIMIZE TABLE`
- 自增列检查会检查 AUTO_INCREMENT 值是否接近对应整数类型的最大值
- 复制检查在 MySQL 8.0.22+ 使用 `SHOW REPLICA STATUS`，旧版本使用 `SHOW SLAVE STATUS`
- 缓冲池检查从 `performance_schema.global_status` 读取 `Innodb_buffer_pool_reads` 和 `Innodb_buffer_pool_read_requests`，计算命中率
- 约束检查列出所有外键约束；MySQL 不支持 PostgreSQL 的未验证约束概念

### `analyze_workload_indexes` 和 `analyze_query_indexes`

- 数据源：`performance_schema.events_statements_summary_by_digest`（workload 模式）
- EXPLAIN 解析：使用 `EXPLAIN FORMAT=JSON`，解析 `query_block` 中的 `access_type`、`table_name`、`attached_condition`
- 索引建议方法：规则引擎评分（`method='dta'`）；MySQL 没有 HypoPG 等假设索引扩展
- 规则评分逻辑：全表扫描查询权重乘以索引大小衰减因子，按评分排序输出建议
- 不支持 `hypothetical_indexes`，MySQL 分支返回明确提示
- `method='llm'` 保留入口，返回暂未接入说明

### `explain_query`

- MySQL 使用 `EXPLAIN FORMAT=JSON`
- `analyze=true` 不支持，因为 MySQL 的 `EXPLAIN ANALYZE` 会实际执行查询，风险较高
- `hypothetical_indexes` 不支持，因为没有 HypoPG 等价扩展

## 达梦数据库支持

使用 `DATABASE_TYPE=dameng` 选择达梦数据库。拆分配置的默认端口是 `5236`，完整 JDBC URL 使用 `jdbc:dm://` 前缀。

### 基础工具

- `execute_sql` 使用项目统一的 JDBC SQL 客户端和访问模式控制。
- `list_schemas` 读取 `ALL_USERS`，返回当前用户可见的用户或 schema。
- `list_objects` 支持 `table`、`view`、`sequence`：
  - `table` 读取 `ALL_TABLES`
  - `view` 读取 `ALL_VIEWS`
  - `sequence` 读取 `ALL_SEQUENCES`
  - `extension` 不适用于达梦，返回明确的不支持说明
- `get_object_details` 对表和视图读取 `ALL_TAB_COLUMNS`、`ALL_CONSTRAINTS`、`ALL_INDEXES`，对序列读取 `ALL_SEQUENCES`。
- `explain_query` 使用达梦 `EXPLAIN`。`analyze=true` 和 `hypothetical_indexes` 当前不支持，会返回明确说明。

### 诊断工具

- `get_top_queries` 优先读取 `V$SQL_HISTORY`，支持 `mean_time`、`total_time`、`executions` 排序。
- `analyze_db_health` 支持 `index`、`connection`、`wait`、`storage`、`sequence`、`buffer`、`constraint`、`all`。
- 健康检查尽量按单项独立执行；某个系统视图不可访问或权限不足时，该项返回退化说明，其它已请求项继续执行。
- `analyze_workload_indexes` 从 `V$SQL_HISTORY` 读取工作负载 SQL，使用只读规则输出索引建议。
- `analyze_query_indexes` 接受最多 10 条 `SELECT` 查询，对每条查询执行达梦 `EXPLAIN` 并输出只读索引建议。
- `method='llm'` 保留入口，当前返回暂未接入说明。

### 限制

- 达梦诊断输出不要求与 PostgreSQL 或 MySQL 完全等价，系统视图、指标名称和计划格式以达梦可用能力为准。
- 索引建议只输出建议，不自动创建索引，也不执行写入操作。
- 达梦支持不以 DBA 或管理员权限为前提；低权限用户可能看到部分诊断项退化。
- 真实达梦实例验证需要用户提供本地实例连接信息，验证时应覆盖所有 MCP 工具，并记录不可访问系统视图对应的退化行为。

## 兼容性规则

只有在保留工具意图、已记录在本文档、并且有测试覆盖时，细微差异才可以接受。新增数据库方言时，应优先提供基础结构查看、受控 SQL 执行和基础执行计划，再逐步补充该数据库原生支持的诊断能力。诊断逻辑通过 `DiagnosticDialect` 接口解耦，Service 层仅负责路由委托。
