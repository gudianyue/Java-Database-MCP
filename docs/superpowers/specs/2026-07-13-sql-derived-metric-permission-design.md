# SQL 驱动的指标权限控制简化设计

## 1. 背景

当前指标权限链路要求 Agent 在调用 `execute_sql`、`explain_query` 和
`analyze_query_indexes` 时同时传入：

- `permission_domain`
- `user_id`
- `metric_scopes`

服务端再从 SQL 中提取 `(quota_id, quota_scene)`，并要求提取结果与
`metric_scopes` 完全相等，最后使用 `user_id` 查询权限 Provider，判断声明范围是否
属于用户授权范围。

这套设计让 Agent 重复描述 SQL 已经表达的指标范围，增加了工具调用复杂度，也容易因
声明和 SQL 不一致产生无业务价值的拒绝。新的设计以 SQL 为唯一的请求范围证据，Agent
只传入 SQL 和 `user_id`。

`user_id` 的真实性不属于本项目的责任范围。传入 `user_id` 的 Agent 或上游系统负责
保证其可信，本项目只按收到的值执行权限查询与校验。

## 2. 目标

- Agent 调用受权限保护的 SQL 工具时只需固定传入 `user_id`。
- 由服务端从 SQL 中提取实际请求的指标范围。
- 仅当 SQL 请求范围是数据库授权范围的子集时放行。
- 放宽不会影响指标范围证明的 `OR`、`NOT` 和有限 `IN` 写法。
- 删除不再承担职责的权限上下文和使用场景抽象。
- 保持 Provider、Redis 缓存和 fail-closed 策略不变。

## 3. 非目标

- 不验证 `user_id` 的身份真实性或来源。
- 不自动改写 SQL，也不注入用户授权过滤条件。
- 不引入数据库 RLS、授权视图或新的权限后端。
- 第一阶段不支持 CTE、子查询、集合运算或多个受保护表。
- 不调整 `RestrictedSqlGuard` 的读写语句安全策略。
- 不构建完整的跨方言 SQL 解析器。

## 4. 核心安全不变量

对于每条引用受保护表的 SQL，服务端必须从 SQL 中证明其数据范围被正向限定在一个
有限、可枚举的 `(quota_id, quota_scene)` 集合内。

设 SQL 提取范围为 `requestedScopes`，Provider 返回的用户授权范围为
`authorizedScopes`，唯一的授权条件是：

```text
requestedScopes ⊆ authorizedScopes
```

无法提取有限范围、Provider 不可用、`user_id` 为空或子集关系不成立时均拒绝执行。

## 5. MCP 工具接口

三个工具调整为：

```text
execute_sql(sql, user_id)
explain_query(sql, analyze, hypothetical_indexes, user_id)
analyze_query_indexes(queries, max_index_size_mb, method, user_id)
```

删除 `permission_domain` 和 `metric_scopes`。这是有意的 MCP 工具 Schema 不兼容变更，
调用方必须同步更新。

Agent 应固定传入当前调用用户的 `user_id`。非受保护 SQL 不触发 Provider 查询；在服务
内部，即使 `user_id` 为空也不影响非受保护 SQL。受保护 SQL 的 `user_id` 为空时拒绝。

`analyze_query_indexes` 对列表中的每条 SQL 分别检查，所有 SQL 共用本次调用的
`user_id`。任意一条受保护 SQL 检查失败，整次调用失败。

## 6. 组件简化

删除以下类型和错误码：

- `PermissionContext`
- `PermissionDomain`
- `PermissionUsage`
- `PERMISSION_SQL_MISMATCH`

`MetricPermissionEnforcer` 的公开授权接口收缩为：

```java
authorize(String sql, String userId)
```

继续保留：

- `MetricSqlInspection`：表示 SQL 是否受保护、是否可检查及提取出的实际范围。
- `MetricScope`：表示一个 `(quota_id, quota_scene)` 元组。
- `PermissionScope`：表示 Provider 返回的用户授权集合。
- `MetricPermissionProvider`：根据 `user_id` 获取授权范围。
- `MetricPermissionEnforcer`：协调 SQL 检查、Provider 调用和子集比较。

`PermissionUsage` 当前不参与任何判断，删除后不补充替代参数。若未来需要按工具区分
审计信息，应在明确出现该需求时单独设计。

## 7. 鉴权数据流

```text
Agent 传入 sql + user_id
        │
        ▼
ConservativeMetricSqlInspector.inspect(sql)
        │
        ├─ 未引用受保护表 ───────────────► 放行，不调用 Provider
        │
        ├─ 引用但无法精确提取范围 ───────► permission_sql_uninspectable
        │
        └─ 返回 requestedScopes
                  │
                  ├─ user_id 为空 ───────► permission_context_missing
                  │
                  ▼
        MetricPermissionProvider.authorizedScopes(user_id)
                  │
                  ├─ Provider 缺失/超时/异常 ─► 对应 Provider 错误
                  │
                  ▼
        requestedScopes ⊆ authorizedScopes
                  │
                  ├─ 否 ─────────────────► permission_denied
                  └─ 是 ─────────────────► 放行
```

Provider 返回 `null` 继续按无权限处理。Provider 超时或异常不得使用过期授权兜底。
Redis 仍是 cache-aside 加速层，不改变每条 SQL 都必须接受 Inspector 检查的要求。

## 8. 第一阶段 SQL 检查范围

### 8.1 总体限制

第一阶段仅处理：

- 单个查询块。
- 最多一个受保护表。
- 受保护表必须存在完整的指标列和场景列正向条件。
- 范围值必须是字符串字面量并能枚举为有限集合。
- 范围谓词必须明确属于受保护表；存在多个普通表时使用别名消除歧义。

继续拒绝：

- 多语句 SQL。
- CTE 和子查询。
- `UNION`、`INTERSECT`、`EXCEPT`、`MINUS`。
- 引用多个受保护表。
- 指标列或场景列上的否定、范围比较、模糊匹配及其他不可枚举表达式。
- 没有完整指标范围条件的受保护 SQL。

### 8.2 等值和元组 IN

继续支持：

```sql
WHERE quota_id = 'A' AND quota_scene = 'default'
```

```sql
WHERE (quota_id, quota_scene)
      IN (('A', 'default'), ('B', 'custom'))
```

### 8.3 独立 IN

新增支持单侧 `IN`：

```sql
WHERE quota_id IN ('A', 'B')
  AND quota_scene = 'default'
```

提取为：

```text
(A, default)
(B, default)
```

新增支持双侧 `IN`：

```sql
WHERE quota_id IN ('A', 'B')
  AND quota_scene IN ('default', 'custom')
```

按 SQL 语义展开为笛卡尔积：

```text
(A, default)
(A, custom)
(B, default)
(B, custom)
```

只有展开后的全部元组都获得授权时才放行。第一阶段不混合元组 `IN` 与独立指标范围
谓词，也不对同一权限列上的多个范围条件做交集推理；此类写法判定为不可检查。

### 8.4 OR

移除“SQL 中出现任何 `OR` 即拒绝”的全局判断。

当 `OR` 仅作用于普通业务列，且外围已经存在完整、正向的指标范围时可以放行：

```sql
WHERE quota_id = 'A'
  AND quota_scene = 'default'
  AND (status = 1 OR status = 2)
```

只要包含 `OR` 的表达式引用指标列或场景列，第一阶段即判定为不可检查，包括：

```sql
WHERE quota_id = 'A' OR quota_scene = 'default'
```

以及语义上可以枚举、但需要分析布尔分支的完整元组写法：

```sql
WHERE (quota_id = 'A' AND quota_scene = 'default')
   OR (quota_id = 'B' AND quota_scene = 'custom')
```

后者留待真实使用需求证明值得增加复杂度后再支持。

### 8.5 NOT

从全局禁止关键字中删除 `not`。当否定只作用于普通业务列时可以放行：

```sql
WHERE quota_id = 'A'
  AND quota_scene = 'default'
  AND status NOT IN ('deleted', 'disabled')
```

任何引用指标列或场景列的否定表达式继续拒绝，例如：

```sql
WHERE quota_id NOT IN ('A', 'B')
  AND quota_scene = 'default'
```

### 8.6 其他查询能力

在 `WHERE` 已经证明完整指标范围的前提下，下列能力不会扩大受保护表的数据范围，可
继续放行：

- 普通表 JOIN。
- 聚合函数和 `GROUP BY`。
- 只影响聚合结果的 `HAVING`。
- `ORDER BY`、`LIMIT`、`OFFSET`。
- 窗口函数和普通表达式。

受保护表的权限范围条件仍必须出现在 Inspector 能验证的顶层 `WHERE` 中。第一阶段不
从 JOIN `ON`、`HAVING` 或派生关系中推导权限范围。

## 9. 与 RestrictedSqlGuard 的边界

`RestrictedSqlGuard` 继续负责受限模式下的语句级安全，例如写语句和多语句控制。
`ConservativeMetricSqlInspector` 只负责判断受保护表的数据范围。

本次不修改 `RestrictedSqlGuard` 的首关键字白名单或写操作拒绝规则。SQL 必须同时通过
语句级安全检查和指标权限检查，两者任一拒绝都不得执行。

## 10. 错误处理

保留：

| 错误码 | 含义 |
| --- | --- |
| `permission_sql_uninspectable` | 命中受保护表，但 SQL 无法提取有限且完整的指标范围 |
| `permission_context_missing` | 受保护 SQL 缺少 `user_id` |
| `permission_denied` | SQL 请求范围不是用户授权范围的子集 |
| `permission_plugin_disabled_or_missing` | 未配置权限 Provider |
| `permission_provider_timeout` | Provider 查询超时 |
| `permission_provider_unavailable` | Provider 查询或数据处理异常 |

删除 `permission_sql_mismatch`，因为不再存在 Agent 声明范围与 SQL 范围的比较。

检查顺序为：受保护表识别、SQL 范围提取、`user_id` 校验、Provider 调用、子集比较。
因此同时存在多个问题时，返回最先发生的稳定错误码。

## 11. 测试策略

### 11.1 Inspector

- 非受保护 SQL 不进入权限分支。
- 等值条件和元组 `IN` 保持原行为。
- 指标列单侧 `IN` 正确提取范围。
- 场景列单侧 `IN` 正确提取范围。
- 双侧 `IN` 正确展开笛卡尔积。
- 普通列上的 `OR`、`NOT` 可检查。
- 指标列或场景列上的 `OR`、`NOT` 不可检查。
- 完整元组的 `OR` 分支第一阶段仍不可检查。
- CTE、子查询、集合运算和多个受保护表仍不可检查。
- 无完整指标条件、非等值条件和谓词归属错误仍不可检查。

### 11.2 Enforcer

- 非受保护 SQL 不校验 `user_id` 且不调用 Provider。
- 受保护 SQL 缺少 `user_id` 返回 `permission_context_missing`。
- SQL 提取范围全部获得授权时放行。
- 任一提取范围未授权时返回 `permission_denied`。
- Provider 缺失、超时、异常和空结果继续 fail-closed。
- 不再存在声明范围不一致测试。

### 11.3 MCP Facade

- 三个 MCP 工具 Schema 不再暴露 `permission_domain` 和 `metric_scopes`。
- 三个工具将 `user_id` 原样交给 Enforcer。
- `analyze_query_indexes` 逐条鉴权，任一失败时不进入实际分析。
- 执行、解释和索引分析均在业务操作前完成鉴权。

### 11.4 回归

- Provider SQL 参数化绑定不变。
- Redis 缓存键、TTL、命中、异常降级和 JSON 边界测试保持通过。
- `RestrictedSqlGuard` 现有测试保持通过。
- 删除三个权限抽象后没有残余引用或无效文档示例。

## 12. 文档与迁移

更新 `docs/permission-control.md` 和 README 中相关工具说明：

- Agent 示例只传 `sql` 和 `user_id`。
- 明确 SQL 是唯一的请求范围证据。
- 明确 `user_id` 真实性由调用方负责。
- 删除声明与 SQL 等值校验及 `permission_sql_mismatch` 说明。
- 补充第一阶段允许和拒绝的 SQL 示例。
- 标注 MCP 工具 Schema 的不兼容变更。

部署侧的受保护表、指标列、场景列、授权查询和 Redis 配置保持不变。

## 13. 后续演进条件

只有实际拒绝日志证明存在稳定需求时，才考虑增加以下能力：

- 完整指标元组的 `OR` 分支分析。
- CTE 和子查询的逐查询块分析。
- 集合运算各分支的独立授权。
- 多个受保护表的逐别名范围证明。

这些能力不能通过简单删除关键字黑名单实现。每项能力都必须保证所有受保护表访问分支
均能提取有限范围，并配套正反例测试后才能放行。
