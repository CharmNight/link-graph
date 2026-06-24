# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 规范,版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 架构索引新增 freshness、slice manifest、持久化 fragment cache、增量失效与 fragment 合并能力,显著提升后续打开架构图、类图、Review Graph 的加载速度。
- fragment DTO 补齐 JVM 符号语义、source range、字段类型引用、资源和 SPI provider 信息,保证缓存恢复接近全量索引语义。
- 扩展 ranked symbol search、项目图查询、semantic seed、`explore_project_context` 和索引 benchmark 能力。
- 增强 Spring endpoint/config binding 关系、Review 证据原因、图可见性原因和 token-aware prompt budget。
- 下发索引 freshness、cache state、visibility reasons 到前端,并在 footer、命令切换、讲解证据与画布默认视图中展示。

### Changed

- 重构图投影与工作流组合边界,收敛 `GraphEditorApplicationService` 作为应用组合根。
- 类图支持拖拽定位后重算连线,并支持按需补齐当前范围调用 / 显式请求完整关系。
- 抽取前端通用组件(Button / Chip / 空状态 / 节点卡片 / 图谱视图 hook),优化 React Flow 视口、拖拽和路由队列。
- 调整工作台 UI 文案、footer 和测试覆盖。

### Fixed

- 修复架构索引缓存与工作台传输的一致性问题。
- 修复类图成员全量展示后的旧入口残留。
- 修复 UI 打开 IDE 时阻塞 JCEF bridge 的问题。
- 调整布局渲染问题。

## [0.1.1] - 2026-05-12

### Added

- 问答模式识别,区分 `只回答 / 审计风险 / 代码调整 / 继续取证` 四类模式。
- 确定性继续取证流水线,支持 Java/Spring 证据解析和证据闸门。
- 源码证据解析、节点映射轨迹、提示词可用性和运行时取证轨迹记录。
- 前端展示问答模式、提示词展开、源码证据卡片和映射轨迹。
- `QaModeContext`:将 `ReplayableQaRequest` 与 `effectiveMode` 绑定为单一上下文。
- `AuditResultNormalizer`:独立负责问答结果按模式归一化与会话合并。

### Changed

- 抽离图编辑器应用边界,用 `GraphEditorApplicationService` 替换旧的 `LinkGraphProjectService` 门面。
- 将 workflow、runtime、debug、diagnostics、planning、request 等能力迁移到 `application/foundation` 边界。
- 新增 application ports、use case、projection service、presenter 和 snapshot adapter,隔离 UI 状态变更。
- 基于 Gson 统一 LLM JSON 构造与解析,补充共享 HTTP client、工具输入解析和网关测试。
- 重构前端展示结构,整理诊断与模型边界。

### Fixed

- `QaModeClassifier` 默认 fallback 从 `REVIEW` 改为 `AUTO`。
- `InvestigationGraphPatchAdapter` 不再生成假的 prompt 预览。

## [0.1.0] - 2026-04-29

### Added

- 首个公开版本。
- 六类工作区视图:事实图、流程图、资源关系视图、架构图、类图、Review Graph。
- Mermaid 导入、导出、校验和差异同步预览。
- 从图节点跳回源码。
- 本地规则路径与可选远程 LLM 配置的讲解、问答、草稿确认、实现建议和代码 diff 流程。
- 通过受控 Agent Runtime 驱动问答、实现建议与代码 diff 流程。
- `LINKGRAPH_DEBUG_TRACE` 驱动的链路耗时 trace,覆盖分析、状态变更、transport、JCEF 启动、前端启动和 ELK 布局。
- ELK 布局封装为支持 Worker 的布局引擎,保留 bundled fallback。
- 插件基线调整为 JDK 17 / IntelliJ Platform 2023.3.4。

[Unreleased]: https://github.com/CharmNight/link-graph/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/CharmNight/link-graph/releases/tag/v0.1.1
[0.1.0]: https://github.com/CharmNight/link-graph/releases/tag/v0.1.0
