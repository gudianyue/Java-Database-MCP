# SQL 授权上下文

本上下文定义 Database MCP 对用户 SQL 进行授权判定时使用的语言。核心只执行授权决定，不解释具体系统的权限语义。

## Language

### 通用 SQL 授权

**SQL 授权请求（SQL Authorization Request）**:
由用户标识和待处理的原始 SQL 共同构成、必须在进入数据库处理前作出授权决定的请求。授权通过后继续处理的 SQL 必须与请求中的 SQL 完全相同。
_Avoid_: 权限上下文、指标权限请求

**SQL 授权器（SQL Authorizer）**:
拥有完整权限语义，并对 SQL 授权请求作出最终授权决定的协作者。
_Avoid_: 权限 Provider、权限查询器、指标 Provider

**授权决定（Authorization Decision）**:
SQL 授权器针对一个 SQL 授权请求给出的最终允许或拒绝结论。
_Avoid_: 授权范围、校验结果

### 指标范围授权

**受保护指标资源（Protected Metric Resource）**:
配置为必须经过指标范围授权才能读取的数据库关系。
_Avoid_: 敏感表、权限表

**指标范围（Metric Scope）**:
由指标标识与业务场景组成的不可拆分授权单元；两个维度必须作为有序二元组整体判断。
_Avoid_: 指标权限、指标集合、场景集合

**请求范围（Requested Scope）**:
从一条 SQL 的范围条件中可证明地派生出的有限指标范围集合。
_Avoid_: 声明范围、调用方范围

**授权范围（Authorized Scope）**:
权限提供方确认某个用户可以访问的指标范围集合。
_Avoid_: 用户权限、角色权限

**可检查 SQL（Inspectable SQL）**:
能够证明其对受保护指标资源的访问恰好落在一个有限、完整请求范围内的查询；语法解析成功本身不代表可检查。
_Avoid_: 可解析 SQL、安全 SQL
