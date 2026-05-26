# Database MCP Java

Database MCP Java 是一个通用数据库 MCP 服务，基于 Java 21、Spring Boot 和 Spring AI MCP Server 构建。当前支持 PostgreSQL 和 MySQL 的基础数据库工具，并提供按数据库方言实现的慢查询统计、健康检查和索引建议。

## 支持范围

### PostgreSQL

- 基础工具：`execute_sql`、`list_schemas`、`list_objects`、`get_object_details`
- 执行计划：`explain_query`，支持文本执行计划；可结合 HypoPG 评估假设索引
- 慢查询统计：`get_top_queries`，依赖 `pg_stat_statements`
- 健康检查：`analyze_db_health`，支持 `index`、`connection`、`vacuum`、`sequence`、`replication`、`buffer`、`constraint`、`all`
- 索引建议：`analyze_workload_indexes`、`analyze_query_indexes`，使用 PostgreSQL `EXPLAIN (FORMAT JSON)` 和 HypoPG 评估候选索引

### MySQL

- 受控 SQL 执行：`execute_sql`
- 结构查看：`list_schemas`、`list_objects`、`get_object_details`
- 执行计划：`explain_query`，使用 `EXPLAIN FORMAT=JSON`
- 慢查询统计：`get_top_queries`，依赖 `performance_schema.events_statements_summary_by_digest`，支持 `mean_time` 和 `total_time`
- 健康检查：`analyze_db_health`，支持 `index`、`connection`、`fragmentation`、`auto_increment`、`replication`、`buffer`、`constraint`、`all`
- 索引建议：`analyze_workload_indexes`、`analyze_query_indexes`，使用 MySQL `EXPLAIN FORMAT=JSON` 和规则引擎评分生成建议
- 不支持项：`explain_query` 的 `analyze=true` 和 `hypothetical_indexes` 暂不支持

## MCP 传输

服务默认启用 HTTP/SSE，默认监听 `127.0.0.1:8000`，SSE 地址为：

```text
http://127.0.0.1:8000/sse
```

常用环境变量：

```bash
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8000
MCP_STDIO_ENABLED=false
```

如需让受信任内网中的 MCP Client 访问，可绑定到所有网卡：

```bash
SERVER_ADDRESS=0.0.0.0
SERVER_PORT=8000
```

本项目当前不内置鉴权。公开网络访问时，应放在具备鉴权和访问控制能力的反向代理或网关之后。

## 数据库连接

可以使用完整 JDBC URL：

```bash
DATABASE_TYPE=postgresql
DATABASE_URI=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres
```

也可以使用拆分配置：

```bash
DATABASE_TYPE=postgresql
DATABASE_HOST=localhost
DATABASE_PORT=5432
DATABASE_NAME=postgres
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres
```

如果同时配置 `DATABASE_URI` 和拆分连接参数，服务优先使用 `DATABASE_URI`。用户名或密码包含特殊字符时，请按 JDBC URL 参数规则编码。

访问模式可通过环境变量配置：

```bash
DATABASE_MCP_ACCESS_MODE=restricted
```

`restricted` 模式用于受控 SQL 执行，`unrestricted` 模式适合本地开发和可信环境。

### PostgreSQL 示例

完整 JDBC URL：

```bash
DATABASE_TYPE=postgresql
DATABASE_URI=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres
```

拆分配置：

```bash
DATABASE_TYPE=postgresql
DATABASE_HOST=localhost
DATABASE_PORT=5432
DATABASE_NAME=postgres
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres
```

### MySQL 示例

完整 JDBC URL：

```bash
DATABASE_TYPE=mysql
DATABASE_URI=jdbc:mysql://localhost:3306/app?user=root&password=secret
```

拆分配置：

```bash
DATABASE_TYPE=mysql
DATABASE_HOST=localhost
DATABASE_PORT=3306
DATABASE_NAME=app
DATABASE_USERNAME=root
DATABASE_PASSWORD=secret
```

## Docker

构建镜像：

```bash
docker build -t database-mcp-java .
```

使用 PostgreSQL JDBC URL 运行：

```bash
docker run --rm -i \
  -p 127.0.0.1:8000:8000 \
  -e SERVER_ADDRESS=0.0.0.0 \
  -e SERVER_PORT=8000 \
  -e DATABASE_TYPE=postgresql \
  -e "DATABASE_URI=jdbc:postgresql://host.docker.internal:5432/postgres?user=postgres&password=postgres" \
  -e DATABASE_MCP_ACCESS_MODE=restricted \
  database-mcp-java
```

使用 MySQL 拆分配置运行：

```bash
docker run --rm -i \
  -p 127.0.0.1:8000:8000 \
  -e SERVER_ADDRESS=0.0.0.0 \
  -e SERVER_PORT=8000 \
  -e DATABASE_TYPE=mysql \
  -e DATABASE_HOST=host.docker.internal \
  -e DATABASE_PORT=3306 \
  -e DATABASE_NAME=app \
  -e DATABASE_USERNAME=root \
  -e DATABASE_PASSWORD=secret \
  -e DATABASE_MCP_ACCESS_MODE=restricted \
  database-mcp-java
```

仓库提供 `docker-compose.yml`，用于本地启动一个 PostgreSQL 示例数据库和 MCP 服务：

```bash
docker compose up --build
```

默认会把 MCP HTTP/SSE 端口映射到宿主机 `127.0.0.1:8000`。如需调整绑定地址或端口：

```bash
DATABASE_MCP_BIND_ADDRESS=0.0.0.0
DATABASE_MCP_HTTP_PORT=8000
docker compose up --build
```

绑定到 `0.0.0.0` 会让宿主机所有网卡暴露 MCP HTTP/SSE 端口，仅应在受信任网络中使用。

## 构建与测试

```bash
mvn test
mvn -DskipTests package
```

`mvn test` 会运行单元测试和 MCP 注解可见性测试。PostgreSQL 集成测试使用 Testcontainers；如果本机 Docker daemon 不可用，相关测试会自动跳过并在日志中说明原因。

## 兼容性说明

不同数据库的系统视图、执行计划格式和诊断指标并不完全等价。项目通过 `DiagnosticDialect` 为 PostgreSQL 和 MySQL 分别实现诊断逻辑；细节边界和前置条件见 [docs/compatibility.md](docs/compatibility.md)。

## 第三方来源

早期实现参考了 `crystaldba/postgres-mcp` 的工具意图和 PostgreSQL 诊断能力。本项目已经改造为通用数据库 MCP 服务，但仍保留原项目许可声明，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可证

本项目使用 [MIT License](LICENSE)。
