# README 更新范围说明

## 1. 文档目的

本文只说明本次 `README.md` 更新涉及的范围，不承担完整架构说明职责。它的目标是防止后续设计文档、实施文档和代码改造过程中出现 README 口径漂移。

对应文件：

- [README.md](../README.md)

## 2. 本次更新原则

本次 README 按重构后的目标版本进行描述，不再采用“现状 / 未来方向”双口径写法。

更新时遵循以下原则：

- README 作为项目总口径文档，统一按目标架构描述。
- 产品术语统一为 `讲解 / 问答 / 草稿 / 实现建议 / 代码`。
- LLM 层统一按 `Agent Runtime + Tool Facade + Artifact` 描述。
- 草稿确认、正式意图、代码草稿、写回边界必须在 README 中被明确表达。
- 不在 README 中展开实现细节、任务拆分和文件级改造清单。

## 3. 本次实际更新范围

### 3.1 项目定位

更新内容：

- 将项目核心链路明确为：
  - `流程图 / 代码双锚点 -> 讲解 / 问答 -> 草稿 -> 实现建议 -> 代码`
- 将“Mermaid 设计意图”收敛为更稳定的“流程图意图”口径。

### 3.2 当前能力范围

更新内容：

- 增加 Agent Runtime 相关表述。
- 增加 Tool Facade 相关表述。
- 增加 Artifact 跨阶段传递相关表述。

目的：

- 让 README 与重构后的目标版本一致。

### 3.3 核心工作流

更新内容：

- 将工作台统一表述为 `讲解 / 问答 / 草稿 / 实现建议 / 代码`。
- 删除 README 中“产品口径 / 代码口径”双口径说明。

目的：

- 避免 README 再次暴露历史命名干扰。

### 3.4 LLM 相关说明

更新内容：

- 将 LLM 执行模型统一描述为受控 Agent Run。
- 明确 Tool Facade 的按需读取职责。
- 明确 Artifact 的跨阶段共享职责。
- 明确流程图意图优先、草稿确认入口、代码草稿不等于直接写回、写回仍受边界控制。
- 明确本地 fallback 和远程失败回退仍然存在。

目的：

- 让 README 在总览层面直接反映重构后的 LLM 架构。

### 3.5 当前状态

更新内容：

- 将 README 的总说明统一到目标架构语义。

目的：

- 防止 README 再被理解为“仅描述旧实现现状”。

## 4. 本次未更新范围

本次没有修改以下公开文档：

- [docs/architecture.md](architecture.md)
- [docs/project-structure.md](project-structure.md)
- [docs/features-and-limitations.md](features-and-limitations.md)
- [docs/development.md](development.md)
- [docs/usage.md](usage.md)

原因：

- 这些文档后续需要按 README 新口径继续同步更新。
- 本次先把 README 调整为总口径基线，后续再逐份对齐其他公开文档。

## 5. 后续建议同步范围

在后续文档同步时，建议按以下顺序推进：

1. `docs/architecture.md`
   - 对齐 Agent Runtime、Tool Facade、Artifact、草稿确认边界。
2. `docs/features-and-limitations.md`
   - 对齐问答口径、运行边界和 fallback 描述。
3. `docs/project-structure.md`
   - 对齐新增的 `llm/runtime`、`llm/tools`、`llm/artifact`、`llm/context`、`llm/capability` 目录。
4. `docs/usage.md`
   - 对齐工作台的产品术语与链路说明。

## 6. 结论

本次 README 更新的作用不是补实现细节，而是先把项目总口径统一到重构后的目标版本。

后续如果其他文档仍保留旧口径，应以 README 当前版本为优先基线继续修正。
