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

数据库连接地址可以通过环境变量配置：

```bash
DATABASE_URI=jdbc:postgresql://localhost:5432/postgres
```

也可以通过 Spring 配置文件配置：

```yaml
postgres-mcp:
  database-uri: jdbc:postgresql://localhost:5432/postgres
```

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

## 测试说明

`mvn test` 会运行单元测试和 MCP 注解可见性测试。PostgreSQL 集成测试使用 Testcontainers；如果本机 Docker daemon 不可用，该集成测试会自动跳过，并在日志中保留原因。

## 开源协议

本项目使用 [MIT License](LICENSE)。

本项目是基于 `crystaldba/postgres-mcp` 的 Java 重新实现与架构迁移。原始项目同样使用 MIT License，相关版权与许可声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
