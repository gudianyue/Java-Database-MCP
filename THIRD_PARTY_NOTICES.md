# 第三方声明

本项目早期实现参考了 `crystaldba/postgres-mcp` 的工具意图和 PostgreSQL 诊断能力。当前项目已改造为通用数据库 MCP 服务，但仍保留原项目许可声明。

原始项目：

- https://github.com/crystaldba/postgres-mcp

原始项目许可证：

- MIT License
- Copyright (c) 2025, Crystal Corp.

根据 MIT License 要求，原始项目的版权声明和许可声明保留如下。

```text
MIT License

Copyright (c) 2025, Crystal Corp.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Alibaba Druid

本项目使用 Alibaba Druid core `1.2.28` 的 SQL parser、AST 和 visitor 能力，不使用其数据源、连接池或监控组件。

- 上游项目：https://github.com/alibaba/druid
- Maven 坐标：`com.alibaba:druid:1.2.28`
- 许可证：Apache License 2.0
- 许可证原文：[LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt)

Apache License 2.0 允许商业使用、复制、修改和再分发，不要求本项目采用相同许可证。发行包含 Druid 的产品时，必须随发行物提供 Apache License 2.0 许可证文本，保留适用的版权、专利、商标和归属声明；如修改 Druid 文件，还必须显著说明修改。该许可证不授予上游商标使用权，软件按“原样”提供且不含担保。
