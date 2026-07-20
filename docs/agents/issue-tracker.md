# Issue tracker: Local Markdown

本仓库的 Issue 与规格（PRD）使用 `.scratch/` 下的本地 Markdown 文件管理。

## Conventions

- 每个功能使用独立目录：`.scratch/<feature-slug>/`。
- 规格文件为 `.scratch/<feature-slug>/spec.md`。
- 实施 Issue 位于 `.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 开始编号；不得把所有 Issue 合并为单个文件。
- 评论与讨论历史追加到文件底部的 `## Comments` 小节。

## Publishing

当技能要求“发布到 Issue Tracker”时，在 `.scratch/<feature-slug>/` 下创建对应 Markdown 文件；目录不存在时一并创建。

## Fetching

当技能要求获取相关 Issue 时，读取用户给出的本地路径或编号对应的 Markdown 文件。

## Wayfinding

- 路线图：`.scratch/<effort>/map.md`。
- 子任务：`.scratch/<effort>/issues/<NN>-<slug>.md`。
- 子任务通过 `Type:`、`Status:` 和可选 `Blocked by:` 行记录类型、状态与依赖。
- 领取任务时先写入 `Status: claimed`；完成后追加 `## Answer` 并改为 `Status: resolved`。
