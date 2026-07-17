# 指标 SQL 权限控制

> 实现基线：指标权限分析已按已接受的方言感知 AST 决策完成迁移；固定使用 Alibaba Druid core `1.2.28`，不保留旧解析器回退。

## 1. 概述

Database MCP 可以对配置为受保护表的指标 SQL 做服务端权限校验。权限范围以 `(quota_id, quota_scene)` 表示，并且只从 SQL 条件派生；调用方不能声明或覆盖请求范围。

该能力默认关闭。`execute_sql`、`explain_query` 和 `analyze_query_indexes` 在执行、解释或分析 SQL 之前都会先经过同一个 Inspector。权限开启时，Inspector 根据当前连接选择 PostgreSQL、MySQL、Doris 或达梦方言，并在识别表之前检查 64 KiB 长度上限、单语句、只读 `SELECT` 和完整 AST 解析；任一条件不能证明时直接按 `permission_sql_uninspectable` fail-closed，即使 SQL 只查询公开表。通过全局检查后，只有能可靠证明未引用受保护表的 SQL 才立即放行；引用受保护表且无法形成有限、完整 `MetricScope` 集合的写法仍按“不可检查即拒绝”处理。权限明确关闭时完全旁路 Druid 和 Provider，其他访问模式与只读 SQL 安全链仍独立生效。

本项目不负责认证用户身份。Agent 或上游系统必须固定传入 `user_id`，并对它的真实性、稳定性以及与最终用户的绑定关系负责。

## 2. 工具请求契约

三个受控工具只接收原有业务参数和 `user_id`，Java 签名为：

```java
executeSql(String sql, String user_id)
explainQuery(String sql, Boolean analyze, List hypotheticalIndexes, String user_id)
analyzeQueryIndexes(List queries, Integer maxIndexSizeMb, String method, String user_id)
```

Agent 必须在每次调用这三个工具时传 `user_id`。`analyze_query_indexes` 会逐条检查 `queries` 中的 SQL，并为每条使用同一个 `user_id`。

`execute_sql` 请求示例：

```json
{
  "sql": "SELECT quota_value FROM gkschema.gk_qta_data WHERE quota_id = 'A' AND quota_scene = 'default'",
  "user_id": "user-123"
}
```

运行时的处理边界如下：

- 权限开启时，Inspector 首先按当前连接方言完成全局 AST 检查；超过 64 KiB、空输入、多语句、非 `SELECT`、解析失败、错误方言、未消费输入或 MySQL/Doris 可执行注释会在识别受保护表之前 fail-closed。
- 权限关闭时，Inspector 在调用 Druid 之前直接旁路，不调用 Provider；这不会绕开 `SqlAccessMode`、只读查询校验或数据库自身权限。
- 通过前置检查且能可靠识别为未引用受保护表的 SQL 才立即放行，不调用权限 Provider；此时即使 `user_id` 为 `null`、空串或全空白也不会阻止执行。
- SQL 引用受保护表时，先确认 SQL 可安全检查，再对 `user_id` 做 `trim` 并校验非空。
- 因此，“可靠识别为非受保护的 SQL 可在空 `user_id` 下执行”是服务端防护边界，不是 Agent 可以省略参数的兼容契约。Agent 仍须始终传入 `user_id`。

其他工具（如 `list_schemas`、`list_objects`、`get_object_details`、`get_top_queries`、`analyze_db_health`、`analyze_workload_indexes`）没有 `user_id` 参数，也不进入本权限链路。

## 3. 鉴权数据流

一次调用按以下顺序处理：

1. `DatabaseToolFacade` 把原 SQL 与 `user_id` 交给 `MetricPermissionEnforcer`。`analyze_query_indexes` 对列表逐条执行此步骤。
2. 权限关闭时，`ConservativeMetricSqlInspector` 不调用 Druid，直接返回非受保护结果；权限开启时，根据当前 `DatabaseType` 选择 Druid 的 PostgreSQL、MySQL、Doris 或达梦方言解析整条 SQL。
3. 启用状态下，空输入、超过 64 KiB、多语句、非 `SELECT`、方言不匹配、解析失败、未消费输入或可执行注释立即 fail-closed，返回 `permission_sql_uninspectable`。
4. 前置检查通过后，Inspector 判断是否引用 `protected-tables` 中的表；只有能可靠识别为未引用受保护表时才立即放行。
5. 命中受保护表后，Inspector 继续检查 SQL 结构，并仅从受保护表的范围条件中派生 `requestedScopes`。
6. SQL 不可检查，或不能形成有限且同时包含两维的完整范围集合时，fail-closed，返回 `permission_sql_uninspectable`。
7. 对 `user_id` 做 `trim`；缺失或为空则返回 `permission_context_missing`。
8. 唯一的 `MetricPermissionProvider` 根据规范化后的 `user_id` 返回 `authorizedScopes`。
9. 仅当 `authorizedScopes.containsAll(requestedScopes)` 时放行。Provider 返回 `null`、空范围或缺少任一请求范围时返回 `permission_denied`。
10. 鉴权通过后才执行、解释或分析原 SQL；批量索引分析逐条使用同一个 `user_id` 授权，任一条拒绝则整批不进入分析。

这里没有调用方范围与 SQL 范围的等值检查：请求范围的唯一来源就是 SQL。

## 4. 可以安全放行的 SQL 形状

以下示例假设 `gkschema.gk_qta_data` 是受保护表，范围列为 `quota_id` 和 `quota_scene`。SQL 能否最终执行还取决于 Provider 是否授权了全部派生范围。

### 4.1 单值双维等值

```sql
SELECT quota_value
FROM gkschema.gk_qta_data
WHERE quota_id = 'A'
  AND quota_scene = 'default'
```

派生范围：`{(A, default)}`。

### 4.2 一侧 `IN`，另一侧等值

```sql
SELECT quota_value
FROM gkschema.gk_qta_data
WHERE quota_id IN ('A', 'B')
  AND quota_scene = 'default'
```

派生范围：`{(A, default), (B, default)}`。反过来写成 `quota_id = 'A' AND quota_scene IN ('default', 'custom')` 也可安全派生。

### 4.3 两侧 `IN` 的有限笛卡尔积

```sql
SELECT quota_value
FROM gkschema.gk_qta_data
WHERE quota_id IN ('A', 'B')
  AND quota_scene IN ('default', 'custom')
```

派生四个范围：`(A, default)`、`(A, custom)`、`(B, default)`、`(B, custom)`。Provider 必须授权全部四个范围。

### 4.4 tuple `IN` 的明确配对

```sql
SELECT quota_value
FROM gkschema.gk_qta_data
WHERE (quota_id, quota_scene) IN (
  ('A', 'default'),
  ('B', 'custom')
)
```

派生范围仅为两个明确配对：`{(A, default), (B, custom)}`，不会生成笛卡尔积。

tuple `IN` 不能再与单列范围条件混用；单列两维条件也不能重复约束同一维。

### 4.5 普通列的附加 `AND` 过滤

范围条件固定后，可以通过 `AND` 增加不引用范围列的普通过滤，包括普通比较、数值条件、括号化 `OR` 与普通 `NOT`：

```sql
SELECT quota_value
FROM gkschema.gk_qta_data
WHERE quota_id = 'A'
  AND quota_scene = 'default'
  AND amount > 0
  AND (status = 'ACTIVE' OR status = 'PENDING')
  AND NOT (is_deleted = 1)
```

这里 `amount`、`status`、`is_deleted` 都只是额外 filter，不属于 scope。`OR` / `NOT` 不得作用于 `quota_id` 或 `quota_scene`；`WHERE` 顶层的 `OR` 仍会拒绝，因为它可能绕过固定范围。

在多表查询中，受保护表只能出现一次，并且范围列必须无歧义地绑定到该表；推荐始终使用受保护表别名限定范围列。

## 5. 继续拒绝的 SQL 边界

以下写法只要涉及受保护表，就返回 `permission_sql_uninspectable`：

- `quota_id` 或 `quota_scene` 上使用 `OR`、`NOT`，包括用 `OR` 拼接多个看似完整的范围元组。
- `WHERE` 顶层使用 `OR`（以及可产生同类逃逸的 `XOR`、`||`）。
- 范围列使用 `>`、`>=`、`<`、`<=`、`!=`、`<>`、`LIKE` 等范围或比较形式。
- 范围值使用参数占位符、列/函数/算术表达式，或使用空、`null` 文本及畸形 `IN` 列表；当前白名单只接受非空字符串字面量。
- 缺少 `quota_id` 或 `quota_scene` 任一维，或者完全没有 `WHERE`。
- tuple `IN` 与单列范围条件混用，或重复约束同一范围维度。
- CTE、子查询、PostgreSQL `TABLE` query expression、`UNION`、`INTERSECT`、`EXCEPT`（以及 `MINUS`）；Inspector 不解释 `TABLE` 这种替代查询表达式，涉及受保护表时会保守拒绝。
- PostgreSQL `ONLY` 关系修饰符，包括 `ONLY table`、`ONLY (table)` 以及可选尾随 `*`；Inspector 暂不解释这些关系语义，涉及受保护表时会保守拒绝，能可靠识别为仅引用公开表的 SQL 仍按 fast path 放行。
- 多个 SQL 语句，或用分号追加另一条语句。受保护 SQL 应只提供单条查询并省略末尾分号。
- 同一 SQL 中出现多个受保护表关系；或范围列绑定到其他表、在多表关系中无法确认归属。
- 受保护表上的非受支持语句（例如当前无法检查的 DML）或其他不在白名单内的结构。

此外，权限开启时，空输入、超过 64 KiB、多语句、非 `SELECT`、错误方言、解析失败、未消费输入、可执行注释和无法安全解释的引用/转义结构都属于全局前置 fail-closed 边界。普通注释和优化器 hint 只有在 Druid 能把整条输入证明为单条受支持 `SELECT` 时才允许；MySQL/Doris 可执行注释始终拒绝。Inspector 在判断表是否受保护之前就拒绝这些输入，因此即使 SQL 只查询公开表也可能返回 `permission_sql_uninspectable`。权限关闭时不执行这些 AST 检查。

示例：

```sql
-- 顶层 OR 可以逃逸固定范围：拒绝
SELECT * FROM gkschema.gk_qta_data
WHERE quota_id = 'A' AND quota_scene = 'default'
   OR status = 'ACTIVE';

-- OR 直接作用于范围列：拒绝
SELECT * FROM gkschema.gk_qta_data
WHERE (quota_id = 'A' OR quota_id = 'B')
  AND quota_scene = 'default';

-- 参数不能形成静态、有限范围：拒绝
SELECT * FROM gkschema.gk_qta_data
WHERE quota_id = ? AND quota_scene = 'default';

-- 缺少一维：拒绝
SELECT * FROM gkschema.gk_qta_data
WHERE quota_id = 'A' AND quota_value > 0;
```

这是建立在完整方言 AST 解析之上的保守安全白名单，而不是对所有可解析 SQL 的授权承诺。业务若需要放宽某种 SQL 形状，应先定义范围传播语义并通过安全测试扩展 Inspector，不能依赖调用方输入或旧解析器回退绕过检查。

## 6. Provider、配置与缓存

### 6.1 受保护对象与范围列

核心配置示例：

```properties
database-mcp.permission.enabled=true
database-mcp.permission.metric.enabled=true
database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data
database-mcp.permission.metric.metric-columns[0]=quota_id
database-mcp.permission.metric.scene-columns[0]=quota_scene
```

权限关闭时，Inspector 的受保护表集合为空，并在调用 Druid 前直接返回，不解析 SQL、检查长度或调用 Provider；其他访问模式、只读 SQL 校验和数据库权限不受影响。权限开启时，`MetricPermissionConfigurationValidator` 要求 `protected-tables`、`metric-columns`、`scene-columns` 均包含非空配置，并且恰好存在一个 `MetricPermissionProvider` Bean；否则启动失败。

受保护表按配置名称或末级表名做大小写归一化匹配。通过全局前置检查后，只有配置为受保护的表进入指标范围鉴权；能可靠识别为未列入的表时立即放行且不调用 Provider。

### 6.2 内置 SQL Provider

Provider 接口为：

```java
PermissionScope authorizedScopes(String userId);
```

内置 `ConfiguredSqlMetricPermissionProvider` 由 `database-mcp.permission.metric.provider.authorization-query` 启用。它把规范化后的 `user_id` 作为绑定参数执行授权查询，避免字符串拼接；授权查询必须恰好包含一个 `?`，或一个 `${user_id}` / `${p_user_id}` / `[${user_id}]` / `[${p_user_id}]` 占位符（后两种也支持外包单引号），并返回：

- `quota_id`：指标 ID；
- `quota_scenes`：该指标授权的一个或多个场景。

`quota_scenes` 可以是可迭代值或分隔字符串；字符串分隔符由 `scene-delimiters` 配置，默认是 `,`、`，`、`;`、`|`。空指标、空场景或缺少返回列会被视为 Provider 异常并 fail-closed。Provider 查询超时默认值是 10 秒，且必须大于 0。

```properties
database-mcp.permission.metric.provider.authorization-query=SELECT quota_id, quota_scenes FROM user_metric_auth WHERE user_id = ?
database-mcp.permission.metric.provider.timeout-seconds=10
```

也可以自行实现 `MetricPermissionProvider` 对接 IAM 或权限中心。使用自定义实现时不要配置内置授权查询，并确保 Spring 容器中最终只有一个 Provider Bean。

### 6.3 Redis 缓存

项目内置 `RedisMetricPermissionCache` 只由内置 `ConfiguredSqlMetricPermissionProvider` 消费，按 `user_id` 的 SHA-256 摘要作为键后缀，缓存该 Provider 返回的完整授权范围：

```properties
database-mcp.permission.metric.provider.cache.enabled=true
database-mcp.permission.metric.provider.cache.ttl-seconds=300
database-mcp.permission.metric.provider.cache.key-prefix=database-mcp:permission:metric:v1:
spring.data.redis.url=<Redis URL>
spring.data.redis.timeout=200ms
```

设置 `cache.enabled=true` 不会自动包装任意自定义 `MetricPermissionProvider`。自定义 Provider 如需缓存，必须自行实现缓存，或显式包装/调用缓存组件。

缓存默认关闭。启用时 `ttl-seconds` 必须大于 0，`key-prefix` 必须非空。缓存命中不续期；读取失败或缓存值损坏时回源 Provider，写入失败不会改变本次 Provider 结果。Provider 本身失败仍按相应错误码 fail-closed。

## 7. 错误码与排查

Facade 会把异常转换为包含下列稳定错误码的文本。启用状态下的全局 AST 检查可能在表识别前返回 `permission_sql_uninspectable`，其余权限错误码产生于受保护 SQL 的鉴权链路。

| 错误码 | 含义 | 责任与排查建议 |
| --- | --- | --- |
| `permission_sql_uninspectable` | 受保护 SQL 无法形成有限、完整且安全的请求范围；或 SQL 在表识别前未通过全局 AST 检查 | SQL 作者：检查长度、单语句、只读 `SELECT`、当前连接方言、完整解析和可执行注释；对受保护 SQL 再按第 4 节白名单检查两维范围、`OR` / `NOT`、参数、集合操作、子查询和表关系 |
| `permission_context_missing` | 受保护 SQL 的 `user_id` 缺失、为空或 trim 后为空 | Agent / 上游：确认每次调用都固定传入真实用户标识；此码不表示范围缺失 |
| `permission_plugin_disabled_or_missing` | 可检查的受保护 SQL 没有可用的 `MetricPermissionProvider` | 部署方：确认权限开启配置、内置授权查询或自定义 Bean；启动校验正常情况下应更早发现此问题 |
| `permission_provider_timeout` | Provider 授权查询超出 `timeout-seconds` | 部署方：检查授权库性能、网络与超时阈值，避免把超时当作无权限 |
| `permission_provider_unavailable` | Provider 发生超时以外的运行异常 | 部署方：检查授权数据源、返回列/值、连接与自定义 Provider 日志 |
| `permission_denied` | Provider 返回的授权范围为 `null`，或不包含 SQL 派生的全部请求范围 | 授权数据负责人：核对该 `user_id` 的 `quota_id` / `quota_scene` 授权；不要通过放宽 SQL 规避 |

Inspector 在 `user_id` 校验之前运行。因此，同一条受保护 SQL 如果既不可检查又缺少 `user_id`，首先得到 `permission_sql_uninspectable`；全局前置检查失败时同样不会进入 `user_id` 或 Provider 校验。

## 8. 与其他安全控制的关系

- 指标范围鉴权只对引用受保护表的 SQL 生效，但权限开启时所有 SQL 都先受 64 KiB、单语句、只读 `SELECT`、当前连接方言和完整 AST 解析的全局 fail-closed 检查约束。本控制不替代 MCP 入口认证；Agent / 网关仍需认证用户并保护 `user_id` 不被伪造。
- 本权限控制与 `SqlAccessMode`（`restricted` / `unrestricted`）正交：访问模式约束 SQL 操作类型，本控制约束能否读取受保护指标范围。
- 本控制在 Java 层完成，不依赖数据库原生 RLS / GRANT；生产环境仍应使用最小权限数据库账号和网络访问控制。
- Provider 查不到用户通常返回空授权范围并导致 `permission_denied`；Provider 查询异常则是 `permission_provider_unavailable`，两者应分别排查。

## 9. 部署核对清单

1. 确认受保护表、指标列和场景列配置准确。
2. 确认 Agent 对三个受控工具始终传入经过认证绑定的 `user_id`。
3. 确认内置授权查询或自定义 Provider 恰好提供一个 Bean。
4. 确认授权结果覆盖 SQL 可能派生的全部笛卡尔积或 tuple 配对。
5. 用第 4、5 节示例验证业务 SQL 形状；不可检查写法应在上线前改写。
6. 若启用 Redis，确认 TTL、键前缀和连接可用，并接受缓存 TTL 内的授权变更延迟。
7. 监控 Provider 超时、不可用、SQL 不可检查和拒绝错误码，分别交由正确责任方处理。
8. 构建或发行前确认依赖树中的 Druid 为 `com.alibaba:druid:1.2.28`，并按 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 随发行物保留 Apache License 2.0 许可证和适用声明。
