# 项目结构说明

## 概览

本仓库是一个单 Gradle 根工程，承载 IntelliJ 插件后端和一个位于 `web/` 的内嵌前端工作区。插件最终只保留一个前端入口：前端构建产物会被复制到生成资源目录下的 `linkgraph/`，工具窗口运行时加载 `linkgraph/index.html`。

## 根目录职责

- `src/main/kotlin`
  - 插件后端主代码。
- `src/main/resources`
  - 插件描述、国际化文案和运行时资源。
- `src/test/kotlin`
  - 后端单元测试与平台测试。
- `src/test/resources/fixtures`
  - 通过 classpath 加载的测试样例。
- `src/integrationTest/kotlin`
  - 通过独立 `integrationTest` source set 编译和执行的集成测试。
- `web/`
  - React + Vite 前端工作区。
- `docs/`
  - 对外公开的项目文档。
- `docs/diagrams/`
  - 文档中使用的架构图等展示资产；`src/` 子目录保存可维护的 Mermaid 源文件，公开文档默认引用生成后的 SVG。

## 后端包结构

`src/main/kotlin/com/charmnight/linkgraph` 按职责拆分：

- `actions`
  - 编辑器和菜单动作入口。
- `toolwindow`
  - 工具窗口生命周期与会话管理。
- `application`
  - 项目级应用边界、用例、工作流、状态投影端口、运行时支撑、调试和诊断能力。
  - `GraphEditorApplicationService` 是 IDE 动作、工具窗口 bridge 和调试自动化进入业务流程的组合根，主要暴露命令分发器。
- `application/command`
  - `ApplicationCommand`、命令分发器和命令处理器，统一承接动作、bridge 和调试请求。
- `application/event`
  - 应用事件边界，只表达应用层结果，不携带 UI view document 或展示状态。
- `application/result`
  - 问答、实现建议、代码 diff、Review 等应用结果模型。
- `application/indexed`
  - 架构图、类图和 Review Graph 的 indexed graph 请求、默认参数和请求归一化。
- `application/usecase`
  - 面向动作和 bridge 的稳定用例入口。
- `application/workflow`
  - 主体分析、架构图、类图、Review Graph、问答、草稿、实现建议、代码 diff、同步预览和导航等流程编排。
- `application/port`
  - 应用层对外依赖端口，例如状态快照、工作区提交和 UI 投影提供者。
- `ui`
  - JCEF 容器、前后端桥接、传输渲染和前端资源加载。
- `ui/bridge`
  - 前端命令 envelope 的解析和后端消息构造。
- `json`
  - 生产代码共享 JSON codec 和脚本安全序列化支撑。
- `projection`
  - 统一图投影内核、窗口裁剪、隐藏计数、overflow 元数据和交互式图投影。
- `presentation`
  - 图展示目标、lane、hidden bucket 等展示契约模型。
- `foundation`
  - 日志、调试环境和 trace 等基础能力。
- `semantic`
  - 语义分析、事实构建与主体定位。
- `architecture`、`jvm`、`source`
  - 项目级 JVM 符号/关系索引、架构图/类图投影和源码内容解析支撑。
- `review`
  - Review Graph 查询、diff 到符号映射、影响面和相关测试证据。
- `model`
  - 共享图模型定义。
- `workbench`
  - 问答会话、草稿确认、风险决策、步骤投影和工作台布局偏好等领域类型与服务。
- `mermaid`、`diff`、`sync`、`navigation`、`codegen`、`llm`、`investigation`、`settings`
  - 各自聚焦的领域能力模块。

`llm` 下按运行职责继续拆分：

- `runtime`
  - 受控 Agent Run、步骤执行、预算和停止策略。
- `tools`
  - 图读取、源码读取、锚点解析、草稿访问等 Tool Facade。
- `artifact`
  - 跨步骤和跨阶段传递的结构化 Artifact。
- `capability`
  - 问答、实现建议、代码 diff 等 Agent Capability。
- `context`
  - 源码片段等上下文收集与去重支撑。

`investigation` 用于风险线程继续取证的完整链路，包括领域模型、流水线编排、证据闸门、取证目标规划、结果展示、结果适配和 resolver 实现。

## 前端结构

`web/src/app` 承载前端工作台与图渲染逻辑：

- `views/fact`
  - 事实图视图模块。
- `views/flowchart`
  - 流程图视图模块。
- `views/resource`
  - 资源关系视图模块。
- `views/architecture`
  - 架构图视图模块。
- `views/class-diagram`
  - 类图视图模块。
- `views/review`
  - Review Graph 视图模块。
- `reactflow`
  - 共享图画布基础设施与布局接线。
- `components`
  - 可复用的面板、弹窗、任务栏、阶段工作台、链路大纲、变更托盘和图动作组件。
- `controllers`
  - bridge 命令、bootstrap 状态和工作台动作的前端协调逻辑。
- `presentation`
  - 三类目标图复用的展示 shell、lane 覆盖层和 presentation 辅助逻辑。
- `graph`
  - 图节点尺寸、节点类型和共享图渲染注册等基础定义。
- `workflow`
  - 工作流阶段、任务栏和阶段状态相关的前端模型。
- `workbench`
  - 讲解、问答、草稿、步骤详情和讨论面板等工作台域界面。

`web/src/test` 承载前端测试代码与测试支撑：

- `setup.ts`
  - Vitest 全局初始化与 Testing Library 扩展。
- `vite-config.test.ts`
  - 校验前端构建与测试约定的配置测试。
- `app/**`
  - 镜像 `web/src/app/**` 的前端单元测试与组件测试目录。

前端测试约定采用“业务代码与测试目录分离”的统一结构：业务代码只放在 `web/src/app`，测试只放在 `web/src/test`。新增前端测试时，应优先放到 `web/src/test/app` 下与被测模块路径对应的位置，而不是回写到 `web/src/app`。

## 构建与打包

前端任务定义在 `build.gradle.kts` 中：

1. `frontendInstall`：在 `web/` 中安装依赖。
2. `frontendTest`：运行前端测试。
3. `frontendBuild`：构建 Vite 应用。
4. `frontendPackResources`：把构建产物复制到生成的插件资源目录。

运行时后端优先从 `linkgraph/index.html` 读取打包后的前端资源。开发和测试场景下，如果打包资源尚不可用，可以回退到 `web/dist`。

## 测试入口

- 后端测试：`./gradlew test`
- 集成测试：`./gradlew integrationTest`
- 完整检查：`./gradlew check`
- 单独运行前端测试：`npm --prefix web test`

前端测试由 Vitest 从 `web/src/test/**/*.test.ts(x)` 统一收集；`web/src/test/setup.ts` 负责全局测试环境初始化。

## 文档边界

`docs/` 根部文档是公开阅读入口。内部计划、设计记录和执行拆分不作为用户文档导航入口。个人工作记录、机器相关验证日志和本机绝对路径不进入公开阅读入口。

本地内部资料可以放在 `docs/internal/`，该目录由 `.gitignore` 忽略，不作为公开仓库内容。

图表源文件可以保留在 `docs/diagrams/src/`，用于维护公开 SVG。公开文档应优先嵌入或链接 SVG，不直接把 Mermaid 源文件作为阅读入口。
