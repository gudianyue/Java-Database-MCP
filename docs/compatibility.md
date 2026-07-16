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
- `analyze_query_indexes` 保留 `max_index_size_mb` 与 `method` 参数，`method` 仅支持 `dta`，为空时默认使用 `dta`。
- `analyze_workload_indexes` 保留 `max_index_size_mb` 与 `method` 参数，`method` 仅支持 `dta`，为空时默认使用 `dta`。
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
- 索引建议方法：规则引擎评分（`method='dta'`）；`analyze_query_indexes` 仅支持 `dta`；MySQL 没有 HypoPG 等假设索引扩展
- 规则评分逻辑：全表扫描查询权重乘以索引大小衰减因子，按评分排序输出建议
- 不支持 `hypothetical_indexes`，MySQL 分支返回明确提示
- `analyze_workload_indexes` 的 `method` 仅支持 `dta`，为空时默认使用 `dta`

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
- `analyze_query_indexes` 接受最多 10 条 `SELECT` 查询，对每条查询执行达梦 `EXPLAIN` 并输出只读索引建议；`method` 仅支持 `dta`，为空时默认使用 `dta`。
- `analyze_workload_indexes` 的 `method` 仅支持 `dta`，为空时默认使用 `dta`。

### 限制

- 达梦诊断输出不要求与 PostgreSQL 或 MySQL 完全等价，系统视图、指标名称和计划格式以达梦可用能力为准。
- 索引建议只输出建议，不自动创建索引，也不执行写入操作。
- 达梦支持不以 DBA 或管理员权限为前提；低权限用户可能看到部分诊断项退化。
- 真实达梦实例验证需要用户提供本地实例连接信息，验证时应覆盖所有 MCP 工具，并记录不可访问系统视图对应的退化行为。

## Apache Doris 数据库支持

使用 `DATABASE_TYPE=doris` 选择 Apache Doris，或通过 `spring.profiles.active=doris` 激活 `application-doris.yml` 完整 profile。Doris 复用 MySQL 客户端协议（默认端口 `9030`，JDBC 前缀 `jdbc:mysql://`，依赖 `mysql-connector-j`），因此不引入新的 JDBC 驱动；它与 `MySqlDatabaseDialect` **不**互通：方言层独立实现，避免 `information_schema` 与 `EXPLAIN` 输出差异被 MySQL 假设吞掉。

### 基础工具

- `execute_sql` 使用项目统一的 JDBC SQL 客户端和 `SqlAccessMode` 访问模式控制，方言层不绕开。
- `list_schemas` 读取 `information_schema.SCHEMATA`，并对系统库（`mysql`、`information_schema`、`performance_schema`、`sys`、`__internal_schema`）打 `System Schema` 标记。
- `list_objects` 支持 `table` / `view`：
  - `table` 读取 `information_schema.TABLES` 且 `TABLE_TYPE='BASE TABLE'`；Doris 的物化视图（`MATERIALIZED VIEW`）在 `TABLE_TYPE` 中也归类为 `BASE TABLE`，会随 `table` 列表返回。
  - `view` 读取 `information_schema.TABLES` 且 `TABLE_TYPE='VIEW'`。
  - `sequence` / `extension` 抛 `UnsupportedOperationException`，消息遵循 `<DB> 不支持 <X> 风格的 <Y>。` 模板。
- `get_object_details` 对表和视图通过 `JSON_ARRAYAGG(JSON_OBJECT(...))` 聚合 `information_schema.COLUMNS`、`KEY_COLUMN_USAGE`、`STATISTICS`；低版本（< 2.0）不支持 `JSON_ARRAYAGG` 时，会进入 `degraded` 路径，返回带有 `"当前 Doris 版本或权限无法获取索引统计"` 说明的 `indexes` 字段。
- `explain_query` 使用 Doris 原生 `EXPLAIN <sql>`（**不**附加 `FORMAT=JSON`），由 `ReadOnlyQueryValidator` 先行拦截非 `SELECT` 与多语句。
- `explain_query` 的 `analyze=true` 与 `hypothetical_indexes` 通过现有 `ExplainPlanService` 路由抛不支持说明（与 MySQL / 达梦一致）。

### 诊断工具

- `get_top_queries` 从 `__internal_schema.audit_log` 聚合 SQL 摘要（`COALESCE(stmt, digest)`），支持 `mean_time` / `total_time` / `executions` 排序；`__internal_schema.audit_log` 在审计插件未启用时该工具返回退化说明。
- `analyze_db_health` 使用**独立的 Doris 原语命名空间**，参数为 `doris_audit_log` / `doris_compaction` / `doris_tablet_health` / `all`（逗号组合），逐项独立执行、失败隔离：
  - `doris_audit_log`：基于 `__internal_schema.audit_log`，输出 `user` / `query` / `query_count` / `last_active` 详细表（10–20 行）。
  - `doris_compaction`：读取 `information_schema.BACKENDS`，输出 `backend_id` / `host` / `alive` / `tablet_num` / `last_heartbeat` 详细表。
  - `doris_tablet_health`：读取 `information_schema.tablets`，输出 `tablet_id` / `backend_id` / `version_count` / `row_count` / `last_check_time` 详细表。
  - `all`：合并上述三段输出。
- `analyze_db_health` 收到 PG / MySQL / 达梦方言遗留的 10 个健康检查名（`vacuum` / `fragmentation` / `sequence` / `auto_increment` / `wait` / `storage` / `replication` / `index` / `connection` / `buffer` / `constraint`）一律抛 `UnsupportedOperationException("Doris 不支持 <X> 风格的 <Y>。")`；收到**未识别**名称则抛 `IllegalArgumentException`（与 MySQL / 达梦一致）。
- `analyze_workload_indexes` 从 `__internal_schema.audit_log` 取高成本查询，结合 `information_schema.COLUMNS` 评估列基数，输出只读索引建议；`method` 仅支持 `dta`，为空时默认使用 `dta`。
- `analyze_query_indexes` 接受最多 10 条 `SELECT`，对每条执行 `EXPLAIN <sql>`（不附加 `FORMAT=JSON`）并输出只读建议；`method` 仅支持 `dta`，为空时默认使用 `dta`。

### 限制

1. `EXPLAIN` 输出为 Doris 文本计划，**不**使用 `EXPLAIN FORMAT=JSON`；下游若需要 JSON 结构，由调用方在客户端解析。
2. 物化视图在 `list_objects(table)` 列表中以 `BASE TABLE` 形式出现，未单独区分 `MATERIALIZED VIEW`。
3. 不支持 `sequence` / `extension` 对象类型；调用方会收到 `UnsupportedOperationException`。
4. `analyze_db_health` 不支持 PG / MySQL / 达梦的 10 个遗留健康检查名（`vacuum` / `fragmentation` / `sequence` / `auto_increment` / `wait` / `storage` / `replication` / `index` / `connection` / `buffer` / `constraint`），统一改用 `doris_*` 原语。
5. `audit_log` 相关诊断依赖审计插件（`enable_audit_plugin=true`）和 `__internal_schema.audit_log` 表存在；未启用时该项返回退化说明，其它检查项继续执行。
6. `SHOW` 语句的封装层不通过方言层实现：Doris 部分 `SHOW` 输出与 MySQL 不完全一致，调用方直接使用 `execute_sql` 即可。
7. `get_object_details` 在 Doris < 2.0 缺少 `JSON_ARRAYAGG` 支持时会走退化路径；建议生产环境使用 Doris ≥ 2.0。
8. `list_objects(table)` 会同时返回普通表与物化视图（`BASE TABLE`），调用方需结合 `information_schema.TABLES` 自行甄别（可通过 `TABLE_COMMENT` 字段辅助判断）。

### 连接配置

```yaml
# application-doris.yml（已随项目提供，可通过 -Dspring.profiles.active=doris 激活）
database-mcp:
  database-type: doris
  database-port: 9030
  # 方式 1：完整 URL（推荐，单实例切换 database）
  database-uri: jdbc:mysql://localhost:9030/example_db?useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull&tinyInt1isBit=false&allowPublicKeyRetrieval=true
  # 方式 2：拆分参数
  # database-host: localhost
  # database-port: 9030
  # database-name: example_db
  # database-username: root
  # database-password: your_password
```

环境变量形式（与 `application.yml` 兼容，无需 profile）：

```bash
export DATABASE_TYPE=doris
export DATABASE_URI='jdbc:mysql://localhost:9030/example_db?useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull&tinyInt1isBit=false&allowPublicKeyRetrieval=true'
```

5 项 Doris MySQL 兼容参数（缺一不可）：

| 参数 | 取值 | 作用 |
|---|---|---|
| `tinyInt1isBit` | `false` | 避免 TINYINT(1) 被当 BIT 处理 |
| `zeroDateTimeBehavior` | `convertToNull` | `0000-00-00` 映射为 NULL 而非抛错 |
| `characterEncoding` | `UTF-8` | 元数据查询使用 UTF-8 解码 |
| `useUnicode` | `true` | 配合 `characterEncoding`，启用 Unicode 传输 |
| `allowPublicKeyRetrieval` | `true` | 本地受信场景可启用；**生产请改 `useSSL=true` 并设为 `false`**，避免中间人风险 |

## 兼容性规则

只有在保留工具意图、已记录在本文档、并且有测试覆盖时，细微差异才可以接受。新增数据库方言时，应优先提供基础结构查看、受控 SQL 执行和基础执行计划，再逐步补充该数据库原生支持的诊断能力。诊断逻辑通过 `DiagnosticDialect` 接口解耦，Service 层仅负责路由委托。
