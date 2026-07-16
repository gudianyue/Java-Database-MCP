# Redis 指标权限缓存设计

## 1. 背景

Java Database MCP 为 Agent 的问数流程提供数据库查询能力。`ask-data` 在一次任务中可能连续执行 `explain_query`、小样本 `execute_sql`、完整 `execute_sql`，并在后续多轮追问中继续查询其他指标或场景。

当前 `MetricPermissionEnforcer` 对每条命中受保护表的 SQL 都调用 `MetricPermissionProvider.authorizedScopes(userId)`。内置 `ConfiguredSqlMetricPermissionProvider` 会执行包含递归组织、角色和用户授权关系的 SQL，重复查询会增加延迟并占用业务数据库连接。

服务当前以单实例运行，但后续会扩展为多实例。因此缓存从第一版开始采用 Redis，避免未来再迁移本地缓存数据和失效语义。

## 2. 目标

- 同一用户在 5 分钟内的多次受保护查询复用完整指标权限范围。
- 当前单实例和未来多实例使用相同缓存模型。
- 数据库继续作为权限权威源，Redis 只作为可丢失的加速层。
- Redis 故障时安全回源数据库，不降低现有 fail-closed 保证。
- SQL 证据解析、调用方声明校验和权限包含校验仍然逐次执行。
- 权限变更最多延迟 5 分钟生效。

## 3. 非目标

第一版不实现以下能力：

- 本地 Caffeine 与 Redis 两级缓存。
- 按 Agent 对话、MCP session 或 transport session 缓存。
- 分布式锁、Redisson 或缓存预热。
- Redis Pub/Sub 权限变更通知。
- 管理端主动清理缓存的接口。
- 把 Redis 变成权限主数据源。
- 为所有自定义 `MetricPermissionProvider` 强制增加缓存。

如果未来出现明显的 Redis 访问瓶颈、同一用户并发缓存击穿，或业务要求权限变更立即生效，再分别评估本地一级缓存、分布式互斥或主动失效；这些能力不提前建设。

## 4. 核心决策

### 4.1 缓存对象

缓存用户的完整指标权限范围：

```text
userId -> PermissionScope(Set<MetricScope>)
```

不缓存以下内容：

- 某条 SQL 的允许或拒绝结果。
- 本次请求声明的 `metric_scopes`。
- SQL 文本或 SQL 哈希。
- conversation ID、MCP session ID。

同一用户切换指标、组织、时间范围或 `quota_scene` 时，仍可复用同一份完整权限范围；每条 SQL 再使用 `containsAll` 判断本次声明是否属于该范围。

### 4.2 缓存位置

Redis 缓存服务于内置的 `ConfiguredSqlMetricPermissionProvider`。该 Provider 的职责变为：

1. 尝试从 Redis 读取该用户的 `PermissionScope`。
2. 命中时直接返回反序列化后的不可变权限范围。
3. 未命中或 Redis 不可用时，执行现有授权 SQL。
4. 授权 SQL 成功后，尽力把结果写入 Redis，再返回新鲜结果。

自定义 Provider 保持现有接口和行为。自定义实现如果需要缓存，可以复用后续落地的具体 Redis 缓存组件，但第一版不增加只有一个实现的通用缓存接口或工厂。

### 4.3 Redis 键

键格式：

```text
database-mcp:permission:metric:v1:<sha256(userId)>
```

- 使用 Java 标准库 SHA-256 计算小写十六进制摘要。
- 不在 Redis key 中暴露原始用户 ID。
- `metric` 隔离权限域。
- `v1` 隔离序列化结构版本；结构不兼容时切换版本，不扫描或批量迁移旧键。

当前设计以 `userId` 全局唯一为前提。若业务用户 ID 可能跨租户重复，必须先把可信 `tenantId` 纳入 `PermissionContext` 和授权查询，键摘要再改为 `sha256(tenantId + ":" + userId)`。仅修改缓存键不能补救现有权限模型中的租户歧义。

### 4.4 Redis 值

使用现有 Jackson 将 `PermissionScope` 序列化为 JSON，示例：

```json
{
  "scopes": [
    {"quotaId": "Q001", "quotaScene": "默认"},
    {"quotaId": "Q002", "quotaScene": "年度"}
  ]
}
```

空权限是有效授权查询结果，保存为：

```json
{"scopes": []}
```

不使用 Java 原生序列化，不保存 SQL、用户资料、加载时间或错误对象。

### 4.5 过期策略

写入时使用固定 TTL：

```text
SET key value EX 300
```

- 默认 TTL 为 300 秒。
- Redis 命中不续期，禁止滑动过期。
- 缓存生命周期不绑定对话生命周期。
- 一次对话超过 5 分钟时，过期后的下一次受保护查询重新加载权限。
- 授权新增和撤销都可能延迟最多 5 分钟生效。
- Redis 自身淘汰键等价于缓存未命中，安全回源数据库。

固定过期保证活跃用户不会因为持续访问而无限使用旧权限。

## 5. 调用流程

每次受保护 SQL 仍按以下顺序处理：

1. `ConservativeMetricSqlInspector` 判断是否命中受保护表并解析 SQL 中的指标范围。
2. 校验 `permission_domain=metric`、可信 `user_id` 和非空 `metric_scopes`。
3. 校验 SQL 解析范围与调用方声明范围完全相等。
4. Provider 按用户查询 Redis。
5. Redis 命中时返回缓存的完整 `PermissionScope`。
6. Redis 未命中或读取失败时执行授权 SQL。
7. 授权 SQL 成功后尽力写入 Redis，固定 TTL 300 秒。
8. Enforcer 校验完整权限范围包含本次声明范围。
9. 全部通过后，才允许原 SQL 进入执行、EXPLAIN 或索引分析。

Redis 只替换第 6 步中的重复授权 SQL，不跳过前置 SQL 解析和声明校验。

## 6. 故障与安全语义

| 场景 | 行为 |
| --- | --- |
| Redis 命中且 JSON 合法 | 使用缓存范围继续包含校验 |
| Redis 未命中 | 查询授权数据库，成功后写缓存 |
| Redis 连接、读取或超时异常 | 记录脱敏警告，直接查询授权数据库 |
| Redis JSON 无法解析 | 尽力删除损坏键，查询授权数据库 |
| 授权数据库成功、Redis 写入失败 | 使用本次新鲜权限继续鉴权，记录脱敏警告 |
| 授权数据库返回空结果 | 缓存空权限，最终返回 `permission_denied` |
| 授权数据库超时 | 不写缓存，返回 `permission_provider_timeout` |
| 授权数据库其他异常 | 不写缓存，返回 `permission_provider_unavailable` |
| Redis 与授权数据库同时失败 | 按授权数据库异常 fail-closed |
| 缓存过期后重新加载失败 | 不使用过期值，fail-closed |

Redis 异常本身不等同于权限 Provider 异常；只要权威数据库仍能成功返回权限，鉴权可以继续。任何错误、超时或非法返回值都不能进入缓存。

调用方提供的 `user_id` 必须来自可信上游身份上下文。Agent 或模型生成的任意文本不能作为可信用户身份；Redis 缓存不能修复身份伪造问题。

## 7. 并发策略

第一版不使用分布式锁。

多个实例在同一用户缓存失效的瞬间并发未命中时，可能分别执行一次授权 SQL，随后写入相同 Redis key。授权结果来自同一权威源，重复写入是幂等的，短暂重复查询可以接受。

只有当运行数据证明同一用户并发导致授权库压力时，才增加缓存击穿治理。届时优先评估短时 Redis `SET NX EX` 锁，并明确等待、锁超时和实例崩溃语义；第一版不承担这部分复杂度。

## 8. 配置

建议新增：

```yaml
database-mcp:
  permission:
    metric:
      provider:
        cache:
          enabled: true
          ttl-seconds: 300
          key-prefix: "database-mcp:permission:metric:v1:"

spring:
  data:
    redis:
      url: ${REDIS_URL}
      timeout: 200ms
```

配置规则：

- `cache.enabled` 默认 `false`，需要部署方显式开启，保证未配置 Redis 的现有部署向后兼容。
- `ttl-seconds` 默认 `300`，`key-prefix` 默认 `database-mcp:permission:metric:v1:`。
- `cache.enabled=false` 时完全保持现有直接查询 Provider 的行为。
- `cache.enabled=true` 时，`ttl-seconds` 必须大于 0，`key-prefix` 必须非空。
- Redis 连接失败不阻止服务回源授权数据库。
- 权限控制总体仍由现有 `database-mcp.permission.enabled` 和 `database-mcp.permission.metric.enabled` 控制。

Redis 使用 Spring Boot 管理的 Lettuce 连接和 `StringRedisTemplate`。不引入 Redisson。

## 9. 可观测性

第一版保持最小可观测性：

- Redis 读取、反序列化和写入失败记录脱敏警告。
- 日志不记录完整权限范围、Redis JSON、SQL 或原始缓存键。
- 正常缓存命中不逐次记录日志，避免问数高频调用产生噪声。

命中率、加载耗时和缓存规模指标等到项目引入统一指标体系后再补充，不为本功能单独引入监控框架。

## 10. 测试设计

至少覆盖以下行为：

1. 同一用户在 TTL 内重复调用只执行一次授权 SQL。
2. 同一用户切换不同 `metric_scopes` 仍复用完整权限范围。
3. 不同用户使用不同 Redis key，权限不串用。
4. 空权限结果可缓存，并稳定拒绝查询。
5. Redis 未命中时查询数据库并以 300 秒 TTL 写入。
6. Redis 读取异常时回源数据库。
7. Redis 内容损坏时删除键并回源数据库。
8. Redis 写入失败不影响本次新鲜鉴权结果。
9. 授权数据库超时和异常不写缓存并保持现有错误码。
10. 过期后加载失败时不使用旧权限。
11. 缓存关闭时不访问 Redis，行为与当前版本一致。
12. SHA-256 key 生成稳定，且 key 不包含原始用户 ID。

现有 `MetricPermissionEnforcerTest` 继续证明 SQL 解析、声明等值校验和包含校验没有被缓存绕过。

## 11. 代码影响范围

实现预计只涉及：

- `pom.xml`：增加 Spring Data Redis starter。
- `DatabaseMcpProperties`：增加 Redis 权限缓存配置。
- `MetricPermissionConfigurationValidator`：校验缓存配置。
- `ConfiguredSqlMetricPermissionProvider`：接入 cache-aside 流程。
- 一个具体的 Redis 权限缓存组件：负责 key、JSON 和 TTL。
- 对应单元测试、`application.yml` 示例和权限控制文档。

不修改 MCP 工具参数，不修改 `PermissionContext`、`MetricPermissionProvider` 接口和 SQL Inspector 契约。

## 12. 验收标准

- Redis 正常、键未被淘汰且缓存写入成功时，同一用户 5 分钟内的连续问数调用不重复执行授权 SQL。
- 多实例共享 Redis 时，同一用户的缓存可被任一实例复用。
- 缓存命中不能跳过 SQL 解析、上下文校验和声明等值校验。
- Redis 故障时能回源数据库；数据库成功则继续鉴权，数据库失败则 fail-closed。
- 缓存过期后绝不使用旧值兜底。
- 权限新增或撤销最多延迟 300 秒生效。
- 不引入本地一级缓存、分布式锁、对话缓存或主动失效机制。
