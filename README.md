# Postgres MCP Java

这是 PostgreSQL MCP 服务的 Java 21 / Spring Boot / Maven 实现线。

## 阶段状态

- 阶段 0：项目骨架和兼容性说明
- 阶段 A：基础数据库访问工具
  - `execute_sql`
  - `list_schemas`
  - `list_objects`
  - `get_object_details`
- 阶段 B：性能诊断工具
  - `explain_query`
  - `get_top_queries`
- 阶段 C：数据库健康检查工具
  - `analyze_db_health`
- 阶段 D：索引调优工具
  - `analyze_workload_indexes`
  - `analyze_query_indexes`

后续阶段会继续加入依赖 LLM 的能力。

## 配置

数据库连接可以通过完整 JDBC URL 配置：

```bash
DATABASE_URI=jdbc:postgresql://localhost:5432/postgres
```

也可以通过 Spring 配置文件配置：

```yaml
postgres-mcp:
  database-uri: jdbc:postgresql://localhost:5432/postgres
```

也可以拆分为主机、端口、库名、用户名和密码：

```bash
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DATABASE=postgres
POSTGRES_USERNAME=postgres
POSTGRES_PASSWORD=postgres
```

对应 Spring 配置：

```yaml
postgres-mcp:
  database-host: localhost
  database-port: 5432
  database-name: postgres
  database-username: postgres
  database-password: postgres
```

如果同时配置了 `DATABASE_URI` 和拆分连接参数，会优先使用 `DATABASE_URI`。

访问模式配置示例：

```yaml
postgres-mcp:
  access-mode: restricted
```

## 命令

```bash
mvn test
mvn -DskipTests package
```

## Docker 部署

构建镜像：

```bash
docker build -t postgres-mcp-java .
```

使用完整 JDBC URL 运行：

```bash
docker run --rm -i \
  -e DATABASE_URI=jdbc:postgresql://host.docker.internal:5432/postgres \
  -e POSTGRES_USERNAME=postgres \
  -e POSTGRES_PASSWORD=postgres \
  postgres-mcp-java
```

使用用户名和密码拆分配置运行：

```bash
docker run --rm -i \
  -e POSTGRES_HOST=host.docker.internal \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_DATABASE=postgres \
  -e POSTGRES_USERNAME=postgres \
  -e POSTGRES_PASSWORD=postgres \
  postgres-mcp-java
```

本仓库也提供 `docker-compose.yml`，用于本地启动一个 PostgreSQL 和 MCP 服务示例：

```bash
docker compose up --build
```

## 测试说明

`mvn test` 会运行单元测试和 MCP 注解可见性测试。PostgreSQL 集成测试使用 Testcontainers；如果本机 Docker daemon 不可用，该集成测试会自动跳过，并在日志中保留原因。

## 开源协议

本项目使用 [MIT License](LICENSE)。

本项目是基于 `crystaldba/postgres-mcp` 的 Java 重新实现与架构迁移。原始项目同样使用 MIT License，相关版权与许可声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
