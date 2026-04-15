# Link Graph

Link Graph 是一个 IntelliJ Platform 插件，用于在项目内查看代码与资源之间的关联关系。它可以从当前方法或资源出发构建图数据，在工具窗口中展示事实图、流程图和资源关系视图，并围绕这张图提供 Mermaid 导入导出、步骤化讲解、多轮审计、草稿确认、实现计划和代码草稿等能力。

## 项目定位

这个项目不是独立桌面程序，也不是通用可视化平台，而是一个运行在 IntelliJ IDEA 内部的开发辅助插件。它的目标是把“当前方法、相关调用链、配置资源、Mermaid 设计意图、草稿确认和代码草稿”收敛到同一个工作台里，降低阅读和修改复杂链路时的上下文切换成本。

## 当前能力范围

- 基于 Kotlin 与 IntelliJ Platform Gradle Plugin 构建的 IDEA 插件
- 通过 JCEF 承载内嵌 React 前端
- 三种主要视图
  - 事实图
  - 流程图
  - 资源关系视图
- Mermaid 导入、导出、校验与差异流程
- 从图节点跳回源码
- 支持本地规则与远程 LLM 配置的讲解、审计、草稿确认、计划和代码草稿流程

## 文档导航

- [快速开始](docs/getting-started.md)
- [使用说明](docs/usage.md)
- [开发说明](docs/development.md)
- [架构说明](docs/architecture.md)
- [项目结构说明](docs/project-structure.md)
- [功能与限制](docs/features-and-limitations.md)

## 仓库结构

- `src/main/kotlin`：插件后端实现
- `src/main/resources`：插件描述、文案资源与打包后的前端资源
- `src/test/kotlin`：单元测试与平台测试
- `src/test/resources/fixtures`：后端测试样例
- `src/integrationTest/kotlin`：集成测试
- `web/`：React + Vite 前端工作区
- `docs/project-structure.md`：目录结构说明
- `docs/architecture.md`：完整架构说明

## 快速上手

1. 安装依赖：`npm --prefix web ci`
2. 运行后端测试：`./gradlew test`
3. 启动插件沙箱：`./gradlew runIde`
4. 在沙箱 IDE 中打开一个 Java 或 Kotlin 项目
5. 通过编辑器右键菜单或工具菜单打开 Link Graph

更详细的步骤见 [docs/getting-started.md](docs/getting-started.md)。
实际功能操作说明见 [docs/usage.md](docs/usage.md)。

## 环境要求

- JDK 17
- 可运行当前前端工具链的 Node.js 与 npm
- 与 `gradle.properties` 中目标平台兼容的 IntelliJ IDEA 版本

## 常用命令

```bash
./gradlew test
./gradlew integrationTest
./gradlew check
./gradlew runIde
npm --prefix web test
```

`./gradlew check` 会执行后端检查和前端测试。`./gradlew runIde` 会启动带插件沙箱的 IDE。

## 核心工作流

- 当前主体链路加载
  - 从当前光标所在的方法或资源主体出发生成事实图，并投影为不同视图。
- 三视图阅读
  - 在事实图、流程图、资源关系视图之间切换观察同一批分析结果。
- Mermaid 设计流
  - 导入 Mermaid、校验问题、对比现有工作图，并生成同步预览。
- 工作台协同
  - 右侧固定工作台提供 `讲解 / 审计 / 草稿 / 计划 / 代码` 五个入口。
  - 讲解输出稳定步骤，审计输出多轮会话、待确认变更和风险线索，草稿是统一业务真相层。
- 实现计划与代码草稿
  - 从已确认草稿层推导实现计划，再生成可写回工程的代码草稿，并支持批量或单条写回。
- 源码导航
  - 从图节点、资源节点或草稿结果回跳到源文件位置。

## LLM 相关说明

插件同时支持本地规则执行和远程 LLM 提供方。远程能力不是默认必需项，需要在插件设置中显式配置。远程执行未启用、配置不完整或调用失败时，部分流程会回退到本地规则或模板结果，而不会伪装成远程请求成功。

当前与 LLM 相关的功能边界和限制见 [docs/features-and-limitations.md](docs/features-and-limitations.md)。

## 当前状态

仓库仍处于持续演进阶段。公开文档只描述当前代码结构和当前支持的工作流，不包含内部设计稿、历史计划或个人工作记录。

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 安全说明

见 [SECURITY.md](SECURITY.md)。

## 许可证

本项目采用 MIT 许可证，见 [LICENSE](LICENSE)。
