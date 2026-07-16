# 指标权限控制 SQL 机制分析

## SQL 总体作用

`D:\projects\skills\codex\指标权限控制.txt` 中的 SQL 用于根据输入用户 `p_user_id` 计算该用户可访问的指标授权范围。它把用户可获得的权限拆成三条来源链路：部门、角色、用户，然后统一到授权资源表 `gkschema.gk_qta_res_obj` 中查询可访问的 `quota_id` 和 `quota_scenes`。

最终输出不是简单的“用户能看哪些指标 ID”，而是“用户能看哪些指标以及这些指标在哪些场景下可用”。因此它天然对应 MCP 改造里需要的 `(quota_id, quota_scene)` 或等价 map 权限模型。

## 输入参数 `p_user_id`

SQL 在 `wt_param` CTE 中定义输入参数：

```sql
select '[${p_user_id}]'::VARCHAR AS p_user_id
```

后续所有权限展开都围绕这个 `p_user_id`：

- 查用户主组织。
- 查用户直接角色。
- 查用户组间接角色。
- 查直接授予该用户的资源权限。

在 MCP 中，`p_user_id` 应映射为工具参数 `user_id`，但本轮 MCP 改造不验证 `user_id` 的可信来源，只把它作为 provider 查询授权范围的输入。

## 权限主体来源：部门、角色、用户

SQL 把权限主体归为三类：

- 部门：用户主组织及递归下级组织。
- 角色：用户直接拥有的角色，以及用户组带来的角色。
- 用户：直接绑定到用户 ID 的授权。

这三类主体最终都通过 `gkschema.gk_qta_res_obj` 的 `res_obj` 和 `res_obj_id` 字段匹配授权资源。

## 部门权限如何通过用户主组织和递归下级组织展开

部门权限从用户主组织开始：

```sql
FROM ibps.ibps_party_org t_po
LEFT JOIN ibps.ibps_party_user t_pu
ON t_pu.main_org_ = t_po.id_
WHERE t_pu.id_ = (SELECT p_user_id FROM wt_param)
AND t_po.org_classify = 'dept'
```

这一步找到用户的主组织，并要求组织分类为 `dept`。随后使用 `WITH RECURSIVE all_data` 递归查找下级组织：

```sql
SELECT t_po.org_code, t_po.parent_code, t_po.name_
FROM ibps.ibps_party_org t_po, all_data parent
WHERE t_po.parent_code = parent.org_code
```

递归结果被放入 `wt_uo`，再通过部门资源授权匹配：

```sql
WHERE t_ro.res_obj = '部门'
AND t_ro.res_obj_id IN (SELECT org_code FROM wt_uo)
```

含义是：用户拥有其主部门以及主部门下级部门关联的指标授权。

## 角色权限如何通过用户直接角色和用户组角色展开

角色权限分两部分：

1. 用户直接角色：

```sql
SELECT t2.role_id_
FROM ibps.ibps_party_user_role t2
WHERE t2.user_id_ = (SELECT p_user_id FROM wt_param)
```

2. 用户组带来的角色：

```sql
SELECT gr.role_id_
FROM ibps.ibps_party_group_role gr
INNER JOIN ibps.ibps_party_user_group ug
ON ug.group_id_ = gr.id_
WHERE ug.user_id_ = (SELECT p_user_id FROM wt_param)
```

两个角色集合通过 `UNION` 合并后，用于匹配授权资源表：

```sql
WHERE t_ro.res_obj = '角色'
AND t_ro.res_obj_id IN (...)
```

含义是：只要指标授权挂在用户直接角色或用户组角色上，该用户都能获得对应指标场景权限。

## 用户权限如何直接匹配用户 ID

用户权限是最直接的授权路径：

```sql
WHERE t_ro.res_obj = '用户'
AND t_ro.res_obj_id IN (SELECT p_user_id FROM wt_param)
```

含义是：如果授权资源表中存在直接绑定当前用户 ID 的授权记录，该用户获得对应的 `quota_id` 和 `quota_scenes`。

## 授权资源表 `gkschema.gk_qta_res_obj`

`gkschema.gk_qta_res_obj` 是该 SQL 的核心授权资源表。三条权限路径都会从这张表读取：

```sql
SELECT t_ro.quota_id, t_ro.quota_scenes
FROM gkschema.gk_qta_res_obj t_ro
```

关键字段含义：

- `res_obj`：授权主体类型。
- `res_obj_id`：授权主体 ID，例如部门编码、角色 ID 或用户 ID。
- `quota_id`：被授权的指标 ID。
- `quota_scenes`：该指标可用的场景集合或场景表达。

## `res_obj` 三类值的含义：部门、角色、用户

`res_obj` 在该 SQL 中出现三种值：

- `部门`：`res_obj_id` 与用户主组织及递归下级组织的 `org_code` 匹配。
- `角色`：`res_obj_id` 与用户直接角色或用户组角色的 `role_id_` 匹配。
- `用户`：`res_obj_id` 与输入 `p_user_id` 直接匹配。

这说明授权模型不是单一用户维度，而是主体聚合模型：用户最终权限等于部门授权、角色授权、用户授权的合并结果。

## 输出结果 `quota_id`、`quota_scenes`

最终输出来自：

```sql
select * from wt_auth
```

`wt_auth` 中三条授权分支均输出：

- `quota_id`
- `quota_scenes`

其中 `quota_id` 表示指标，`quota_scenes` 表示该指标适用的场景。由于三条分支之间使用 `UNION`，完全相同的 `(quota_id, quota_scenes)` 行会被去重。

## 为什么它是“指标 + 场景”的授权模型

该 SQL 的授权结果不是只返回 `quota_id`，而是同时返回 `quota_id` 和 `quota_scenes`。这意味着同一个指标在不同场景下可能有不同授权结果。

例如，一个用户可能被授权：

- 指标 A 的默认场景。
- 指标 B 的自定义场景。

这不能拆成独立的指标集合 `{A, B}` 和场景集合 `{默认, 自定义}`，否则会错误推导出用户也拥有“指标 A 的自定义场景”或“指标 B 的默认场景”。因此在 MCP 中必须把它建模成 `(quota_id, quota_scene)` 元组，或 `quota_id -> quota_scene set` 的等价 map。

## 适合在 MCP 中抽象成的 provider / scope 结构

建议 MCP 中把该 SQL 封装为 Apboa metric permission provider：

- provider 输入：`user_id`。
- provider 执行：参数化执行该授权 SQL。
- provider 输出：归一化后的指标场景授权范围。

推荐结构：

```text
MetricPermissionProvider
  resolveAuthorizedScopes(user_id) -> PermissionScope

PermissionScope
  scopes: Set<MetricScope>

MetricScope
  quota_id: string
  quota_scene: string
```

如果为了查询和比较效率使用 map，也应保持等价元组语义：

```text
Map<quota_id, Set<quota_scene>>
```

MCP 的 `PermissionEnforcer` 应拿 provider 结果与 Agent 声明的 `metric_scopes` 比较，再与 SQL 中实际出现的指标和场景谓词比较。三者必须一致。

## 风险点

### `user_id` 可信来源

SQL 完全信任输入的 `p_user_id`。如果调用方可以伪造 `user_id`，就可能查询他人的授权范围。MCP 本轮改造不做登录鉴权，也不验证 `user_id` 来源，因此必须由上游网关、调用方身份体系或部署环境保证 `user_id` 可信。

### `quota_scenes` 多值归一化

`quota_scenes` 可能表示多个场景。provider 不能把它当成不可拆分字符串直接粗放比较，也不能与 `quota_id` 脱钩。应按 Apboa 语义把多值拆成明确的 `(quota_id, quota_scene)` 元组，再进入 MCP 授权比较。

### 递归组织范围

部门权限会从用户主组织递归到下级组织。需要确认组织树是否存在环、异常父子关系或过深层级。provider 执行失败、递归异常或超时时，MCP 必须 fail-closed。

### `UNION` 去重

SQL 使用 `UNION` 合并部门、角色、用户三类授权，会对完全相同的行去重。这有利于减少重复授权结果，但也意味着 provider 侧不应依赖重复行表达优先级。MCP 只应关注最终是否拥有某个明确 `(quota_id, quota_scene)`。

### 权限 SQL 失败必须 fail-closed

如果授权 SQL 执行失败、超时、provider 缺失、返回格式异常或 `quota_scenes` 无法归一化，MCP 不能继续放行指标 SQL。正确行为是拒绝并返回脱敏原因，例如 `permission_provider_unavailable`、`permission_provider_timeout` 或 `permission_denied`。

## 与 MCP 改造的关系

这份 SQL 可以作为 MCP 指标权限改造中的 Apboa 默认 provider 数据来源。它负责回答“某个 `user_id` 被授权了哪些指标和场景”，但它本身不是完整安全边界。

完整 MCP 改造还需要服务端做三层校验：

1. provider 使用该 SQL 获取用户授权范围。
2. MCP 校验 Agent 传入的结构化声明 `metric_scopes` 是否在授权范围内。
3. MCP 检查用户 SQL 中实际访问的指标和场景是否与声明一致。

因此，该 SQL 对应的是权限模型中的“授权来源”，而 MCP `PermissionEnforcer` 才是执行期强制边界。只有当 provider 授权、结构化声明和 SQL 证据三者一致时，`execute_sql`、`explain_query`、`analyze_query_indexes` 才能继续处理用户 SQL。
