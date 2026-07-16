# 01 — 让查询索引分析仅支持 DTA

**What to build:** 将 `analyze_query_indexes` 收紧为只支持 `dta` 的端到端 MCP 能力。客户端省略 `method` 或传入空白值时继续使用 `dta`；任何其他值都在 MCP 工具边界被明确拒绝，不进入权限查询、数据库方言或 SQL 执行。四种数据库方言不再保留该工具的 LLM 判断和占位响应，同时继续提供原有权限校验、只读 SQL 校验和 DTA/规则索引建议。

**Blocked by:** None — can start immediately

**Status:** resolved

- [x] `analyze_query_indexes` 的工具名称、查询列表、大小预算、`method` 和 `user_id` 契约保持存在，MCP 参数说明只宣传 `dta` 及其默认行为。
- [x] `method` 为 `null`、空字符串或纯空白时被规范化为 `dta`，显式 `dta` 调用保持原有结果。
- [x] 任意非 `dta` 方法在 MCP 工具边界返回“仅支持 dta”的参数错误，且权限提供方、数据库方言和 SQL 客户端均未被调用。
- [x] 方法校验通过后，每条查询仍先完成 `user_id` 权限校验，再进入当前数据库方言的索引分析。
- [x] PostgreSQL、MySQL、Doris 和达梦不再包含查询索引分析的 LLM 分支、占位消息或专用占位测试。
- [x] 四种数据库原有的查询数量限制、只读 SQL 校验、扩展检查、执行计划分析、大小预算和 DTA/规则建议行为保持不变。
- [x] MCP Schema 和工具发现测试证明该工具仍可发现、`user_id` 仍使用 snake_case，并且参数说明不再宣传 LLM。
- [x] README 和兼容性说明中与查询索引分析相关的内容只描述实际支持的 DTA/规则能力，不再承诺 LLM 入口或未来接入。
- [x] 查询索引分析相关的工具门面、MCP 契约、权限顺序和四种数据库回归测试全部通过。

## Comments

- 已在 MCP 工具门面统一规范化和校验查询索引分析方法，并删除四种数据库方言的查询侧 LLM 占位分支。
- 定向测试、编译和完整 Maven 测试套件均通过；无 Docker 环境下的 Testcontainers 集成测试按项目既有规则自动跳过。
- 规范审查与规格审查均无剩余问题。
