# 权限控制说明

---

## 1. 一句话总结

Database MCP 在原有结构查看 / SQL 执行 / 诊断工具之上，新增了一层**面向「指标类敏感表」的强制权限控制**。该控制默认**关闭**，由部署方按需开启；开启后，访问受保护表的 SQL 必须同时通过「调用方声明 + 服务端 SQL 解析 + 鉴权后端核验」三道关，任一不过即拒绝执行。

它解决的是：「指标数据按 `quota_id / quota_scene` 维度授权」这一具体业务场景下，**谁能在 MCP 里跑哪些 SQL** 的问题。

---

## 2. 控制范围：管什么、不管什么

### 2.1 强制鉴权（涉及以下 MCP 工具的「指标类 SQL」分支）

- `execute_sql`：执行 SQL
- `explain_query`：解释 SQL（含 `analyze` / `hypothetical_indexes`）
- `analyze_query_indexes`：对一批 SQL 做索引分析（**逐条**鉴权）

只要被调用的 SQL **引用了受保护表**，就会进入鉴权链路。

### 2.2 不强制鉴权（保持原行为）

- `list_schemas`、`list_objects`、`get_object_details`：结构查看类
- `get_top_queries`、`analyze_db_health`、`analyze_workload_indexes`：诊断类（只读汇总）

这三类工具**始终**无需 `permission_domain / user_id / metric_scopes` 入参，也不查任何鉴权后端。

### 2.3 受保护的对象

通过配置项 `database-mcp.permission.metric.protected-tables` 显式声明，例如：

```
database-mcp.permission.metric.protected-tables[0]=gkschema.gk_qta_data
```

- 只接受**白名单**形式：未列出的表**不**在权限控制范围之内，原样放行。
- 表名按 `schema.table` 归一化比较（忽略大小写与下划线差异）。

---

## 3. 调用方契约：调用时必须多带三个参数

`execute_sql` / `explain_query` / `analyze_query_indexes` 在调用指标类 SQL 时，必须显式提供：

| 参数 | 含义 | 是否必填 |
| --- | --- | --- |
| `permission_domain` | 权限域。当前固定取值 `metric`，传 `none` 或留空等同放弃鉴权 | 指标类 SQL 必填 |
| `user_id` | 调用方业务用户 ID | 指标类 SQL 必填 |
| `metric_scopes` | 一组 `{quota_id, quota_scene}` 元组，表示**本条 SQL 实际要查的指标范围** | 指标类 SQL 必填 |

请求示例：

```json
{
  "sql": "SELECT quota_value FROM gkschema.gk_qta_data WHERE quota_id = 'Q001' AND quota_scene = 'monthly'",
  "permission_domain": "metric",
  "user_id": "zhangsan",
  "metric_scopes": [
    { "quota_id": "Q001", "quota_scene": "monthly" }
  ]
}
```

> **关键约束**：调用方声明的 `metric_scopes` 必须**与 SQL WHERE 子句中能从受保护表上提取出的范围完全一致**，否则被拒。详见第 6 节。

---

## 4. 鉴权数据流（一次成功调用）

```
                    ┌─────────────────────────────────────┐
 MCP Client ─►►►    │  DatabaseToolFacade.executeSql      │
                    │  → MetricPermissionEnforcer.authorize│
                    └────────────────┬────────────────────┘
                                     │
            ┌────────────────────────┼─────────────────────────┐
            ▼                        ▼                         ▼
  ConservativeMetricSqlInspector   PermissionContext      MetricPermissionProvider
  解析 SQL：                       组装：                  (ConfiguredSql 实现)：
   · 是否引用受保护表？              · permission_domain     · 注入授权查询 SQL
   · WHERE 范围是什么？             · user_id               · 绑定 user_id 参数
   · 是否落到 {quota_id, scene}?   · metric_scopes         · 跑一次 SQL
                                                             · 返回该用户的授权范围
            │                        │                         │
            └────────► 等值校验 ◄────┴────── 包含校验 ◄───────┘
                          │
                          ▼
                    全部通过 → 放行执行原 SQL
```

### 关键步骤

1. **静态解析**：`ConservativeMetricSqlInspector` 对 SQL 做白名单式解析，识别是否引用受保护表、WHERE 范围是否可解析到 `(quota_id, quota_scene)` 元组。
2. **上下文校验**：调用方必须给出 `permission_domain=metric`、非空 `user_id`、非空 `metric_scopes`。
3. **声明 vs 解析等值校验**：解析出的元组集合 **必须等于** 声明的 `metric_scopes`（集合相等，不是包含）。
4. **授权后端核验**：通过 `MetricPermissionProvider` 拉取该用户的所有授权范围，要求声明的 `metric_scopes` 是其**子集**。

任一环节失败，调用立即被拒，原 SQL **不会执行**。

---

## 5. 鉴权后端（MetricPermissionProvider）

### 5.1 抽象接口

服务内置 `MetricPermissionProvider` 函数式接口：

```java
PermissionScope authorizedScopes(String userId);
```

服务**仅提供一个内置实现**：`ConfiguredSqlMetricPermissionProvider`。

### 5.2 内置实现：`ConfiguredSqlMetricPermissionProvider`

- 由配置项 `database-mcp.permission.metric.provider.authorization-query` **存在**时启用（`@ConditionalOnProperty`）。
- 执行该 SQL（将 SQL 中 `${user_id}` / `[${user_id}]` / `'[${user_id}]'` / `${p_user_id}` 等占位符统一替换为 `?`，再以参数化方式绑定 `userId`）。
- **必须返回两列**：`quota_id`、`quota_scenes`（不区分大小写、忽略下划线）。
- `quota_scenes` 字段会被按分隔符拆分，默认分隔符为 `,` `，` `;` `|`。
- 默认查询超时 10 秒（可通过 `database-mcp.permission.metric.provider.timeout-seconds` 调整）。

典型授权查询示例：

```sql
SELECT quota_id, quota_scenes
FROM gkschema.user_metric_auth
WHERE user_id = ?
```

对应一行记录形如：

| user_id | quota_id | quota_scenes |
| --- | --- | --- |
| zhangsan | Q001 | monthly,quarterly |
| zhangsan | Q002 | yearly |

则 `zhangsan` 的授权范围 = `{(Q001, monthly), (Q001, quarterly), (Q002, yearly)}`。

### 5.3 Redis 权限缓存

内置 `ConfiguredSqlMetricPermissionProvider` 可通过 `database-mcp.permission.metric.provider.cache.enabled=true` 开启 Redis cache-aside：

- 缓存 key 为 `database-mcp:permission:metric:v1:<sha256(userId)>`，即对 `user_id` 做 SHA-256 后拼接前缀，不暴露原始 `userId`。
- 缓存 value 是该用户完整的 `PermissionScope` JSON。缓存条目从成功写入 Redis 时开始计时，经过配置的 `ttl-seconds` 后过期；读取命中不续期。对话生命周期与缓存无关；对话持续超过某条目的剩余 TTL 时，该条目过期后的下一次受保护查询会重新加载权限。
- Redis miss、连接失败、读取超时或缓存值损坏时，直接回源授权数据库；损坏值会尽力删除。
- 授权数据库查询成功后会尽力写入 Redis；写入失败仍使用本次查询得到的新鲜权限继续鉴权。
- 授权数据库 timeout 或其他失败仍分别返回 `permission_provider_timeout` / `permission_provider_unavailable`，绝不使用过期值兜底，保持 fail-closed。
- 空权限是合法且可缓存的结果，后续 `containsAll` 校验仍会拒绝无权请求。
- Redis 是可丢失的共享加速层，不是权限权威源，可供多实例共享。第一版不提供 L1、本地缓存、分布式锁、Pub/Sub、对话缓存或主动失效。
- 缓存命中只省去重复的授权 SQL，不绕过每次 SQL Inspector、上下文/声明等值校验和 `containsAll` 校验。
- 权限新增或撤销的可见性延迟最长为实际配置的 `ttl-seconds`，默认 300 秒。

### 5.4 自定义实现

如需对接统一 IAM / 外部权限中心，可实现 `MetricPermissionProvider` 接口并注册为 Spring Bean。系统要求**同时只能存在一个**实现。

---

## 6. SQL 解析规则（白名单式、保守）

`ConservativeMetricSqlInspector` 采用「**无法解析即拒绝**」的保守策略，目的不是做完整 SQL 解析器，而是保证安全：

### 6.1 必须满足的条件（缺一即拒）

1. **单查询块**：SQL 内不能出现未转义的分号（`;`）。
2. **禁止的关键字**：不得包含 `union` / `intersect` / `except` / `minus` / `not` / `with`。
3. **不得使用 `OR`**：WHERE 中出现 `or` 视为无法收敛范围。
4. **受保护表必须唯一**：FROM/JOIN 中只能命中**一个**受保护表。
5. **必须存在 WHERE 谓词**，且谓词能落到受保护表的 `(quota_id, quota_scene)` 两列上。
6. **引用形式**：通过 `schema.table` 或纯表名引用均可识别。

### 6.2 提取出来的范围

解析成功后，Inspector 返回一组 `MetricScope(quotaId, quotaScene)`，要求：

- 与调用方声明的 `metric_scopes` **集合完全相等**（顺序无关）。
- 如果声明多个 scope，WHERE 也必须能与之**一一对应**。

### 6.3 解析失败的典型场景

| 写法 | 原因 | 行为 |
| --- | --- | --- |
| `SELECT … WHERE quota_id = ? OR quota_scene = ?` | 含 `OR`，无法收敛 | 拒绝 |
| `SELECT … FROM a JOIN b …` | 命中两个表且都受保护 | 拒绝 |
| `WITH t AS (…) SELECT …` | 出现 CTE 关键字 `with` | 拒绝 |
| `SELECT … FROM gkschema.gk_qta_data WHERE quota_value > 0` | 没有 `quota_id` / `quota_scene` 谓词 | 拒绝 |
| `SELECT … FROM gkschema.gk_qta_data` | 无 WHERE | 拒绝 |
| `SELECT … UNION SELECT …` | `union` 关键字 | 拒绝 |

---

## 7. 错误码与原因（按出现顺序）

调用被拒时，MCP 客户端会收到形如 `"错误：permission_xxx"` 的返回。**全部以 `permission_` 开头**便于业务侧识别：

| 错误码 | 触发条件 | 责任方 |
| --- | --- | --- |
| `permission_sql_uninspectable` | SQL 不满足第 6 节的解析条件 | 调用方 / SQL 作者 |
| `permission_context_missing` | 调用方未传 `permission_domain=metric` 或缺 `user_id` 或缺 `metric_scopes` | 调用方 |
| `permission_sql_mismatch` | SQL 解析出的范围 ≠ 调用方声明的 `metric_scopes` | 调用方 |
| `permission_plugin_disabled_or_missing` | 服务未配置任何 `MetricPermissionProvider` 实现 | 部署方 |
| `permission_provider_timeout` | 鉴权后端 SQL 超过 `timeout-seconds` | 部署方（鉴权库性能 / 网络） |
| `permission_provider_unavailable` | 鉴权后端 SQL 其他异常 | 部署方（鉴权库可用性） |
| `permission_denied` | 鉴权后端返回的范围**不包含**声明的范围 | 业务授权数据 |

> 部署方排查顺序建议：`plugin_disabled_or_missing` → `provider_unavailable` / `provider_timeout` → `sql_uninspectable` / `context_missing` / `sql_mismatch` → `denied`。

---

## 8. 两种运行模式

### 8.1 关闭模式（默认）

- `database-mcp.permission.enabled=false`（或未设置）
- `ConservativeMetricSqlInspector` 加载的受保护表为空，**所有 SQL 均不受检**。
- 三个强制鉴权工具可以省略 `permission_domain / user_id / metric_scopes` 入参，行为与旧版本一致。

### 8.2 开启模式

使用内置 SQL Provider 的权限基础配置示例：

```
database-mcp.permission.enabled=true
database-mcp.permission.metric.enabled=true
database-mcp.permission.metric.protected-tables[0]=<schema>.<table>
database-mcp.permission.metric.metric-columns[0]=quota_id
database-mcp.permission.metric.scene-columns[0]=quota_scene
database-mcp.permission.metric.provider.authorization-query=<授权 SQL，含一个 ? 占位符>
database-mcp.permission.metric.provider.timeout-seconds=10
```

启用指标权限时，`MetricPermissionConfigurationValidator`（`InitializingBean`）仍按原规则强制检查 `protected-tables`、`metric-columns`、`scene-columns`，并要求恰好注册一个 `MetricPermissionProvider` Bean；上述示例使用内置 SQL Provider，因此配置了 `authorization-query`。

Redis 缓存是可选能力，`cache.enabled` 默认为 `false`。如需启用，可在上述基础上追加：

```
database-mcp.permission.metric.provider.cache.enabled=true
database-mcp.permission.metric.provider.cache.ttl-seconds=300
database-mcp.permission.metric.provider.cache.key-prefix=database-mcp:permission:metric:v1:
spring.data.redis.url=<Redis URL>
spring.data.redis.timeout=200ms
```

启用缓存时，`ttl-seconds` 必须大于 0、`key-prefix` 必须非空；两者的默认值分别为 `300` 和 `database-mcp:permission:metric:v1:`。`spring.data.redis.url` / `spring.data.redis.timeout` 是 Spring Redis 连接配置，不由 `MetricPermissionConfigurationValidator` 作为权限配置必填项检查；实际启用缓存时，部署方应提供可用的 Redis 连接。

启动校验不通过的常见情形：

- 任意一个白名单（`protected-tables` / `metric-columns` / `scene-columns`）为空或全空白
- 没有注册任何 `MetricPermissionProvider` Bean
- 注册了多个 `MetricPermissionProvider` Bean（要求恰好一个）

---

## 9. 需求方需要提供的输入清单

要让权限控制**可运行且无歧义**，需求 / 业务侧需向部署方明确以下事项：

1. **受保护表的清单**：`schema.table` 列表，每个表必须是「按指标授权」的对象。
2. **指标列与场景列的名称**：默认约定为 `quota_id` / `quota_scene`，如业务方使用其它字段名，需在下发配置时改写 `metric-columns` / `scene-columns`。
3. **授权数据来源**：是直接查业务库（`ConfiguredSqlMetricPermissionProvider` 一条 SQL 解决），还是对接 IAM / 权限中心（需要自实现 `MetricPermissionProvider`）。
4. **授权表的查询 SQL**：含一个 `?` 占位符（或 `${user_id}` / `[${user_id}]` 等约定写法），返回 `quota_id` / `quota_scenes` 两列；如 `quota_scenes` 不是字符串而是用其它分隔符或数组，需在配置中调整 `scene-delimiters`。
5. **是否允许 `OR` / CTE / 多表连接**：默认**不允许**。若业务确实有此类需求，需要走「放宽白名单」的代码变更流程。
6. **鉴权后端超时阈值**：默认 10 秒，按数据库性能约定。
7. **错误处理策略**：MCP 客户端拿到 `permission_xxx` 错误码时如何呈现给最终用户（前端建议直接映射为 403 + 原因）。

---

## 10. 与既有功能的关系

- **与 `SqlAccessMode`（`restricted` / `unrestricted`）正交**：本权限控制独立于 SQL 执行模式的访问控制。`restricted` 控制「能否执行任意写语句」，本控制控制「能否读受保护的指标表」。
- **与现有诊断工具无侵入**：`get_top_queries` / `analyze_db_health` / `analyze_workload_indexes` 不引用受保护表，因此不会被本控制拦截。
- **与达梦 / MySQL / PostgreSQL 的方言差异解耦**：控制层完全在 Java 层做 SQL 解析与权限校验，不依赖数据库原生 RLS / GRANT。

---

## 11. 常见误区

- **「开启权限控制后，所有 SQL 都会被检查」**：错。只对**引用受保护表**的 SQL 生效。
- **「调用方声明的范围越小越安全」**：错。声明范围必须**与 SQL WHERE 解析出的范围完全一致**，否则会被 `permission_sql_mismatch` 拒掉。
- **「`metric_scopes` 是查询结果的过滤条件」**：错。它是**声明**，不是过滤；过滤由 SQL 本身负责。
- **「鉴权后端查不到用户就是拒绝」**：错。后端返回空结果会得到 `permission_denied`；查询异常会得到 `permission_provider_unavailable`，二者不同。
- **「可以同时启用多种鉴权后端」**：错。系统强制要求**恰好一个** `MetricPermissionProvider` Bean。

---

## 12. 变更影响面

- 新增包：`dev.databasemcp.permission`（enforcer、inspector、provider、context、scope 等）
- 修改 MCP 入口：`DatabaseToolFacade` 的 `execute_sql` / `explain_query` / `analyze_query_indexes` 三个工具签名追加三个可选参数
- 新增配置前缀：`database-mcp.permission.*`
- 新增启动校验：`MetricPermissionConfigurationValidator`
- 对其余 5 个 MCP 工具、方言实现、SQL 客户端**无侵入**

涉及的具体代码位置（供开发 / 运维核对）：

| 关注点 | 位置 |
| --- | --- |
| 入口与参数注入 | `src/main/java/dev/databasemcp/mcp/DatabaseToolFacade.java:75,94,162` |
| 鉴权协调 | `src/main/java/dev/databasemcp/permission/MetricPermissionEnforcer.java:23` |
| SQL 解析 | `src/main/java/dev/databasemcp/permission/ConservativeMetricSqlInspector.java:49` |
| 鉴权后端默认实现 | `src/main/java/dev/databasemcp/permission/ConfiguredSqlMetricPermissionProvider.java:62` |
| 启动校验 | `src/main/java/dev/databasemcp/permission/MetricPermissionConfigurationValidator.java:24` |
| 配置项结构 | `src/main/java/dev/databasemcp/config/DatabaseMcpProperties.java:124,136,158,207` |

---

## 13. 版本记录

- 当前版本：随本次提交首次引入「指标类 SQL 权限控制」
- 默认行为：关闭；不传新增参数时与旧版本行为完全一致（向后兼容）
