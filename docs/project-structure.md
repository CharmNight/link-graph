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
- `ui`
  - JCEF 容器、前后端桥接、传输渲染和前端资源加载。
- `services`
  - 项目级编排服务与工作流协调。
- `semantic`
  - 语义分析、事实构建与主体定位。
- `model`
  - 共享图模型定义。
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
  - 问答、计划、代码生成等 Agent Capability。
- `context`
  - 源码片段等上下文收集与去重支撑。

`investigation` 用于风险线程继续取证相关的适配和归一化逻辑。

## 前端结构

`web/src/app` 承载前端工作台与图渲染逻辑：

- `views/fact`
  - 事实图视图模块。
- `views/flowchart`
  - 流程图视图模块。
- `views/resource`
  - 资源关系视图模块。
- `reactflow`
  - 共享图画布基础设施与布局接线。
- `components`
  - 可复用的面板、弹窗、工具栏和图动作组件。
- `controllers`
  - bridge 命令、bootstrap 状态和工作台动作的前端协调逻辑。
- `workbench`
  - 工作台外壳与停靠布局。

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

`docs/` 目录只保留公开项目文档。内部计划、私有工作记录和机器相关验证日志不进入公开仓库。

图表源文件可以保留在 `docs/diagrams/src/`，用于维护公开 SVG。公开文档应优先嵌入或链接 SVG，不直接把 Mermaid 源文件作为阅读入口。
