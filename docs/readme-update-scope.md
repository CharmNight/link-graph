# README 更新范围说明

## 1. 文档目的

本文只说明本次 `README.md` 更新涉及的范围，不承担完整架构说明职责。它的目标是防止后续设计文档、实施文档和代码改造过程中出现 README 口径漂移。

对应文件：

- [README.md](../README.md)

## 2. 本次更新原则

本次 README 按重构后的目标版本进行描述，不再采用“现状 / 未来方向”双口径写法。

更新时遵循以下原则：

- README 作为项目总口径文档，统一按目标架构描述。
- 产品术语统一为 `理解链路 / 核验证据 / 风险问答 / 草稿确认 / 代码落地`，阶段内承载讲解、证据、问答、草稿、实现建议和代码 diff。
- LLM 层统一按 `Agent Runtime + Tool Facade + Artifact` 描述。
- 草稿确认、正式意图、代码 diff、写回边界必须在 README 中被明确表达。
- 不在 README 中展开实现细节、任务拆分和文件级改造清单。

## 3. 本次实际更新范围

### 3.1 项目定位

更新内容：

- 将项目核心链路明确为：
  - `流程图 / 代码双锚点 -> 理解链路 / 核验证据 / 风险问答 -> 草稿确认 -> 实现建议 -> 代码 diff / 写回`
- 将“Mermaid 设计意图”收敛为更稳定的“流程图意图”口径。

### 3.2 当前能力范围

更新内容：

- 增加 Agent Runtime 相关表述。
- 增加 Tool Facade 相关表述。
- 增加 Artifact 跨阶段传递相关表述。
- 增加架构图、类图和 Review Graph 的当前能力口径。

目的：

- 让 README 与重构后的目标版本一致。

### 3.3 核心工作流

更新内容：

- 将工作台统一表述为 `理解链路 / 核验证据 / 风险问答 / 草稿确认 / 代码落地` 五阶段，并说明右侧阶段工作台承载讲解、证据、问答、草稿和代码 diff。
- 删除 README 中“产品口径 / 代码口径”双口径说明。

目的：

- 避免 README 再次暴露历史命名干扰。

### 3.4 LLM 相关说明

更新内容：

- 将 LLM 执行模型统一描述为受控 Agent Run。
- 明确 Tool Facade 的按需读取职责。
- 明确 Artifact 的跨阶段共享职责。
- 明确流程图意图优先、草稿确认入口、代码 diff 生成不等于直接写回、写回仍受边界控制。
- 明确本地 fallback 和远程失败回退仍然存在。

目的：

- 让 README 在总览层面直接反映重构后的 LLM 架构。

### 3.5 当前状态

更新内容：

- 将 README 的总说明统一到目标架构语义。

目的：

- 防止 README 再被理解为“仅描述旧实现现状”。

## 4. 后续同步状态

README 更新后，以下公开文档已经按当前问答模式、取证和 Artifact 口径继续同步：

- [docs/architecture.md](architecture.md)
- [docs/project-structure.md](project-structure.md)
- [docs/features-and-limitations.md](features-and-limitations.md)
- [docs/usage.md](usage.md)

仍未纳入本轮同步的公开文档：

- [docs/development.md](development.md)

原因：

- `development.md` 主要描述本地开发、构建和测试流程，本轮问答协议、取证和展示变更没有改变其核心操作口径。

## 5. 后续建议同步范围

后续如果继续扩展问答协议、Artifact 类型或源码取证范围，应同步检查：

1. `README.md`
2. `docs/usage.md`
3. `docs/features-and-limitations.md`
4. `docs/architecture.md`
5. `docs/project-structure.md`

## 6. 结论

本次 README 更新的作用不是补实现细节，而是先把项目总口径统一到重构后的目标版本。

后续如果其他文档仍保留旧口径，应以 README 当前版本为优先基线继续修正。
