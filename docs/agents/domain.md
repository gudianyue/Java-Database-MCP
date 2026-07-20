# Domain Docs

本文件说明工程技能在探索代码前如何使用本仓库的领域文档。

## Before exploring

- 阅读根目录 `CONTEXT.md`，并采用其中定义的规范术语。
- 阅读 `docs/adr/` 中与当前工作相关的架构决策。
- 文件不存在时静默继续；只有在术语或决策实际形成时，才通过领域建模流程按需创建。

## Layout

本仓库采用单上下文布局：

```text
/
├── CONTEXT.md
├── docs/adr/
└── src/
```

## Vocabulary

Issue、规格、测试名称和设计说明应使用 `CONTEXT.md` 中的规范术语，避免使用其中明确列出的同义词。

## ADR conflicts

如果新工作与现有 ADR 冲突，必须显式指出冲突及重新评估的理由，不得静默覆盖既有决策。
