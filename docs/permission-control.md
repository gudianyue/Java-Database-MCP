# SQL 授权与指标权限实现

> 实现基线：通用核心只执行 `SqlAuthorizer` 的授权决定；现有指标权限分析作为可选内置实现，固定使用 Alibaba Druid core `1.2.28`，不保留旧解析器回退。

## 1. 概述

Database MCP 提供开放式 SQL 授权边界。权限开启后，`execute_sql`、`explain_query` 和 `analyze_query_indexes` 把未经修改的 `user_id` 与原始 SQL 交给唯一的 `SqlAuthorizer`；核心不解释角色、租户、指标或其他业务权限语义，也不允许授权器改写 SQL。

权限默认关闭。关闭时通用授权入口完全旁路，不校验 SQL 或 `user_id`，也不要求存在授权器。开启时必须恰好装配一个 `SqlAuthorizer` Bean；缺少或存在多个实现都会使应用启动失败。自定义实现返回 `true` 表示允许、`false` 表示业务拒绝；抛出 `SqlAuthorizationTimeoutException` 表示超时，其他运行异常表示授权器不可用。

当前内置指标实现仍可对配置为受保护表的指标 SQL 做服务端权限校验。权限范围以 `(quota_id, quota_scene)` 表示，并且只从 SQL 条件派生；调用方不能声明或覆盖请求范围。

选择指标授权器时，Inspector 根据当前连接选择 PostgreSQL、MySQL、Doris 或达梦方言，并在识别表之前检查 64 KiB 长度上限、单语句、只读 `SELECT` 和完整 AST 解析；任一条件不能证明时 fail-closed，即使 SQL 只查询公开表。调用方统一得到 `permission_denied`，服务端失败日志以 `sql_uninspectable` 诊断类别保留细分原因。通过全局检查后，只有能可靠证明未引用受保护表的 SQL 才立即放行；引用受保护表且无法形成有限、完整 `MetricScope` 集合的写法仍按“不可检查即拒绝”处理。

本项目不负责认证用户身份。Agent 或上游系统必须固定传入 `user_id`，并对它的真实性、稳定性以及与最终用户的绑定关系负责。通用核心只用 `isBlank()` 判断缺失，有效值不做 trim 并原样传给授权器。

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

- 权限关闭时完全旁路通用授权入口；这不会绕开 `SqlAccessMode`、只读查询校验或数据库自身权限。
- 权限开启时，`null` 或全空白 SQL 先按普通参数错误拒绝，不调用授权器。
- SQL 有效后检查 `user_id`；缺失或全空白返回 `permission_context_missing`，所有外部 SQL 都遵守这一规则。
- 核心把未经 trim 或改写的 `user_id` 与原始 SQL 交给唯一授权器。`false` 返回 `permission_denied`；超时返回 `permission_authorizer_timeout`；其他异常返回 `permission_authorizer_unavailable`。
- 选择指标授权器时，随后按当前连接方言完成全局 AST 检查；超过 64 KiB、多语句、非 `SELECT`、解析失败、错误方言、未消费输入或 MySQL/Doris 可执行注释会在识别受保护表之前 fail-closed。

其他工具（如 `list_schemas`、`list_objects`、`get_object_details`、`get_top_queries`、`analyze_db_health`、`analyze_workload_indexes`）没有 `user_id` 参数，也不进入本权限链路。

## 3. 鉴权数据流

一次调用按以下顺序处理：

1. `DatabaseToolFacade` 把原 SQL 与 `user_id` 交给 `SqlAuthorizationEnforcer`。`analyze_query_indexes` 在任何分析开始前对列表逐条执行此步骤。
2. 权限关闭时立即旁路；权限开启时校验空 SQL 与空 `user_id`，再调用唯一的 `SqlAuthorizer`。自定义实现的决定到此完成。
3. 选择兼容指标实现时，`MetricPermissionEnforcer` 作为 `SqlAuthorizer` 根据当前 `DatabaseType` 选择 Druid 的 PostgreSQL、MySQL、Doris 或达梦方言解析整条 SQL。
4. 指标实现的全局 AST 检查失败时返回通用业务拒绝；调用方得到 `permission_denied`，服务端记录 `sql_uninspectable` 诊断类别。
5. 前置检查通过后，Inspector 判断是否引用 `protected-tables` 中的表；只有能可靠识别为未引用受保护表时才立即放行。
6. 命中受保护表后，Inspector 继续检查 SQL 结构，并仅从受保护表的范围条件中派生 `requestedScopes`。
7. SQL 不可检查，或不能形成有限且同时包含两维的完整范围集合时，fail-closed，返回通用业务拒绝。
8. 唯一的 `MetricPermissionProvider` 根据指标实现内部规范化后的 `user_id` 返回 `authorizedScopes`；这不改变通用核心向 `SqlAuthorizer` 原样传值的契约。
9. 仅当 `authorizedScopes.containsAll(requestedScopes)` 时放行。Provider 返回 `null`、空范围或缺少任一请求范围时返回 `permission_denied`。
10. 鉴权通过后才执行、解释或分析同一条原始 SQL；批量中任一条拒绝则整批不进入分析。

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

以下写法只要涉及受保护表，就拒绝授权；调用方统一得到 `permission_denied`，具体 `sql_uninspectable` 原因只进入服务端诊断日志：

- `quota_id` 或 `quota_scene` 上使用 `OR`、`NOT`，包括用 `OR` 拼接多个看似完整的范围元组。
- `WHERE` 顶层使用 `OR`（以及可产生同类逃逸的 `XOR`、`||`）。
- 范围列使用 `>`、`>=`、`<`、`<=`、`!=`、`<>`、`LIKE` 等范围或比较形式。
- 范围值使用参数占位符、列/函数/算术表达式，或使用空、`null` 文本及畸形 `IN` 列表；当前白名单只接受非空字符串字面量。
- 缺少 `quota_id` 或 `quota_scene` 任一维，或者完全没有 `WHERE`。
- tuple `IN` 与单列范围条件混用，或重复约束同一范围维度。
- CTE、子查询、PostgreSQL `TABLE` query expression、`UNION`、`INTERSECT`、`EXCEPT`（以及 `MINUS`）；Inspector 不解释 `TABLE` 这种替代查询表达式，涉及受保护表时会保守拒绝。
- PostgreSQL `ONLY` 关系修饰符，包括 `ONLY table`、`ONLY (table)` 以及可选尾随 `*`；Inspector 暂不解释这些关系语义，因此在公开资源 fast path 之前统一拒绝。
- 多个 SQL 语句，或用分号追加另一条语句。受保护 SQL 应只提供单条查询并省略末尾分号。
- 同一 SQL 中出现多个受保护表关系；或范围列绑定到其他表、在多表关系中无法确认归属。
- 受保护表上的非受支持语句（例如当前无法检查的 DML）或其他不在白名单内的结构。

此外，权限开启且选择指标授权器时，空输入、超过 64 KiB、多语句、非 `SELECT`、错误方言、解析失败、未消费输入、可执行注释和无法安全解释的引用/转义结构都属于全局前置 fail-closed 边界。普通注释和优化器 hint 只有在 Druid 能把整条输入证明为单条受支持 `SELECT` 时才允许；MySQL/Doris 可执行注释始终拒绝。Inspector 在判断表是否受保护之前就拒绝这些输入，因此即使 SQL 只查询公开表也可能得到 `permission_denied`。权限关闭或未选择指标授权器时不执行这些 AST 检查。

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

## 6. 授权器、Provider、配置与缓存

### 6.1 自定义 SQL 授权器

不使用内置指标实现时，开启全局权限并提供一个 `SqlAuthorizer` Spring Bean：

```properties
database-mcp.permission.enabled=true
database-mcp.permission.metric.enabled=false
```

```java
@Bean
SqlAuthorizer sqlAuthorizer() {
    return (userId, sql) -> permissionService.isAllowed(userId, sql);
}
```

接口返回 `true` 或 `false`，不得返回改写后的 SQL。实现超时应抛 `SqlAuthorizationTimeoutException`，其他运行异常会统一按授权器不可用处理。权限开启时零个或多个 `SqlAuthorizer` 都会拒绝启动；权限关闭时不要求唯一性，也不调用授权器。

### 6.2 HTTP SQL 授权器

非 Java 权限系统可以通过固定 HTTP 协议接入，无需实现项目 Java 接口：

```yaml
database-mcp:
  permission:
    enabled: true
    http:
      url: https://permission.example.com/sql/authorize
      timeout: 3s
      headers:
        Authorization: "Bearer <部署密钥>"
        X-Api-Key: "<部署密钥>"
```

配置 `http.url` 会自动贡献一个 `SqlAuthorizer` Bean，不需要也不支持授权器类型枚举。`timeout` 是单次请求总超时，默认 3 秒且必须大于零。`headers` 是仅由部署方提供的静态请求头；三个 MCP 工具没有对应参数，调用者不能提供或覆盖这些值。不要把真实密钥提交到版本库，应通过受控配置源或环境注入。

适配器对每条 SQL 发送一次请求，不重试也不缓存：

```http
POST /sql/authorize
Content-Type: application/json

{"userId":"user-123","sql":"SELECT 1"}
```

请求 JSON 只包含原始字符串 `userId` 与 `sql`。远端只有返回 2xx 且正文只包含布尔 `allowed` 才形成有效决定：`{"allowed":true}` 放行，`{"allowed":false}` 是业务拒绝并映射为 `permission_denied`；额外字段也视为非法响应。HTTP 403 不是业务拒绝；它与其他非 2xx、空正文、缺失/错误类型字段、连接失败一同映射为 `permission_authorizer_unavailable`，总超时映射为 `permission_authorizer_timeout`。

静态请求头与远端响应正文不会进入应用日志或外部异常消息。首版不提供 OAuth 刷新、动态令牌、请求签名、mTLS 编排、熔断或通用授权缓存；需要这些能力时应提供自定义 `SqlAuthorizer`。

### 6.3 受保护对象与范围列

核心配置示例：

```properties
database-mcp.permission.enabled=true
database-mcp.permission.metric.enabled=true
database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data
database-mcp.permission.metric.metric-columns[0]=quota_id
database-mcp.permission.metric.scene-columns[0]=quota_scene
```

权限关闭时通用授权入口完全旁路；其他访问模式、只读 SQL 校验和数据库权限不受影响。选择指标实现时，`MetricPermissionConfigurationValidator` 要求 `protected-tables`、`metric-columns`、`scene-columns` 均包含非空配置，并且恰好存在一个 `MetricPermissionProvider` Bean；否则启动失败。

受保护表按配置名称或末级表名做大小写归一化匹配。通过全局前置检查后，只有配置为受保护的表进入指标范围鉴权；能可靠识别为未列入的表时立即放行且不调用 Provider。

### 6.4 内置 SQL Provider

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

### 6.5 Redis 缓存

项目内置 `RedisMetricPermissionCache` 只由内置 `ConfiguredSqlMetricPermissionProvider` 消费，按 `user_id` 的 SHA-256 摘要作为键后缀，缓存该 Provider 返回的完整授权范围：

```properties
database-mcp.permission.metric.provider.cache.enabled=true
database-mcp.permission.metric.provider.cache.ttl-seconds=300
database-mcp.permission.metric.provider.cache.key-prefix=database-mcp:permission:metric:v1:
spring.data.redis.url=<Redis URL>
spring.data.redis.timeout=200ms
```

设置 `cache.enabled=true` 不会自动包装任意自定义 `MetricPermissionProvider`。自定义 Provider 如需缓存，必须自行实现缓存，或显式包装/调用缓存组件。

缓存默认关闭。启用时 `ttl-seconds` 必须大于 0，`key-prefix` 必须非空。缓存命中不续期；读取失败或缓存值损坏时回源 Provider，写入失败不会改变本次 Provider 结果。Provider 本身失败仍会 fail-closed：超时映射为授权器超时，其他异常映射为授权器不可用。

## 7. 错误码与排查

Facade 会把异常转换为包含下列四个稳定错误码的文本。自定义、指标及后续其他授权器都使用同一组公开错误；指标细分原因只进入服务端诊断日志。

| 错误码 | 含义 | 责任与排查建议 |
| --- | --- | --- |
| `permission_context_missing` | 任一外部 SQL 的 `user_id` 缺失或全空白 | Agent / 上游：确认每次调用都固定传入真实用户标识；核心不会 trim 有效值 |
| `permission_denied` | 当前 SQL 授权器明确返回拒绝 | 授权实现负责人：核对该 `user_id` 与原始 SQL；核心不会泄露自定义拒绝细节 |
| `permission_authorizer_timeout` | SQL 授权器明确报告超时；指标实现中包括 Provider 超时 | 部署方：检查授权器、授权库性能、网络和内部超时配置 |
| `permission_authorizer_unavailable` | SQL 授权器发生其他运行异常 | 部署方：检查授权器诊断日志与 Spring 装配；正常情况下无效装配应在启动时失败 |

旧指标错误码迁移关系：`permission_sql_uninspectable` 改为 `permission_denied`；`permission_provider_timeout` 改为 `permission_authorizer_timeout`；`permission_provider_unavailable` 和运行期 `permission_plugin_disabled_or_missing` 改为 `permission_authorizer_unavailable`。指标配置或 Provider 缺失现在应在启动阶段失败。

通用核心在调用任何授权器前检查 `user_id`。因此同一条指标 SQL 如果既不可检查又缺少 `user_id`，首先得到 `permission_context_missing`；只有上下文有效后才进入指标 Inspector。

## 8. 与其他安全控制的关系

- 指标范围鉴权只对引用受保护表的 SQL 生效；权限开启且选择指标授权器时，所有 SQL 都先受 64 KiB、单语句、只读 `SELECT`、当前连接方言和完整 AST 解析的全局 fail-closed 检查约束。本控制不替代 MCP 入口认证；Agent / 网关仍需认证用户并保护 `user_id` 不被伪造。
- 本权限控制与 `SqlAccessMode`（`restricted` / `unrestricted`）正交：访问模式约束 SQL 操作类型，本控制约束能否读取受保护指标范围。
- 本控制在 Java 层完成，不依赖数据库原生 RLS / GRANT；生产环境仍应使用最小权限数据库账号和网络访问控制。
- Provider 查不到用户通常返回空授权范围并导致 `permission_denied`；Provider 查询异常则是 `permission_authorizer_unavailable`，两者应结合服务端诊断类别分别排查。

## 9. 部署核对清单

1. 确认 Agent 对三个受控工具始终传入经过认证绑定的 `user_id`。
2. 确认权限开启时恰好存在一个 `SqlAuthorizer` Bean。
3. 使用指标实现时，确认受保护表、指标列、场景列以及唯一 `MetricPermissionProvider` 配置准确。
4. 确认授权结果覆盖 SQL 可能派生的全部笛卡尔积或 tuple 配对。
5. 用第 4、5 节示例验证业务 SQL 形状；不可检查写法应在上线前改写。
6. 若启用 Redis，确认 TTL、键前缀和连接可用，并接受缓存 TTL 内的授权变更延迟。
7. 监控四类公开权限错误，并按服务端 `provider_timeout`、`provider_unavailable`、`sql_uninspectable`、`scope_denied` 等诊断类别分派责任方。
8. 构建或发行前确认依赖树中的 Druid 为 `com.alibaba:druid:1.2.28`，并按 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 随发行物保留 Apache License 2.0 许可证和适用声明。
