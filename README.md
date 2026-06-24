<div align="center">

# Link Graph

**IntelliJ IDEA 内的代码链路阅读、核验与修改工作台**<br>
事实图 · 流程图 · 资源关系 · 架构图 · 类图 · Review Graph

[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ_Platform-2023.3%2B-000000?logo=intellijidea&logoColor=white)](https://plugins.jetbrains.com/docs/intellij/intellij-platform.html)
[![JDK](https://img.shields.io/badge/JDK-17-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/github/license/CharmNight/link-graph?color=blue)](LICENSE)
[![Latest Tag](https://img.shields.io/github/v/tag/CharmNight/link-graph?label=latest%20tag)](CHANGELOG.md)

</div>


---

## 目录

- [它解决什么问题](#它解决什么问题)
- [特性](#特性)
- [安装](#安装)
- [快速上手](#快速上手)
- [环境要求](#环境要求)
- [工作台概览](#工作台概览)
- [LLM 能力说明](#llm-能力说明)
- [文档导航](#文档导航)
- [仓库结构](#仓库结构)
- [参与贡献](#参与贡献)
- [行为准则](#行为准则)
- [安全披露](#安全披露)
- [许可证](#许可证)

---

## 它解决什么问题

阅读和修改复杂代码链路时,工程师通常要在多个工具之间频繁切换:看代码用 IDE,画流程图用外部工具,取证用搜索,改代码用编辑器,记录草稿用笔记。每次切换都丢上下文,每次回到代码都要重新建立心智模型。

Link Graph 是一个 IntelliJ Platform 插件,把这些动作收敛到一个工作台里。它从当前方法或资源出发,自动构建主体链路图,围绕这张图提供:

- 结构化阅读(事实图、流程图、资源关系、架构图、类图、Review Graph)
- 证据核验(节点跳回源码、证据强度、证据缺口、风险线程)
- 草稿确认(明确业务真相层,再生成代码)
- 代码落地(基于已确认草稿生成 diff,批量或单条写回)

核心产品链路:

`流程图 / 代码双锚点 -> 理解链路 / 核验证据 / 风险问答 -> 草稿确认 -> 实现建议 -> 代码 diff / 写回`

## 特性

### 图分析与展示

- 基于当前方法或资源生成功事实图
- 六类工作区视图
  - **事实图** — 当前方法与上下游依赖
  - **流程图** — 方法内部的控制流和步骤
  - **资源关系视图** — 代码与 XML / SQL / 配置 / 文档的连接
  - **架构图** — 项目级 module / package / service / resource / layer 聚合
  - **类图** — JVM 类型关系(class、interface、enum、annotation、record、object)
  - **Review Graph** — 当前 diff / 工作区变更命中的符号、上下游和相关测试
- 从图节点、资源节点或草稿结果跳回源码位置

### Mermaid 工作流

- Mermaid 导入、导出、校验
- 与当前工作图做差异分析和同步预览
- 适合"设计图 vs 现状代码图"对照

### 工作台流程

- 顶部任务栏提供 `理解链路 / 核验证据 / 风险问答 / 草稿确认 / 代码落地` 五阶段流程
- 步骤化讲解,稳定输出
- 多轮问答会话,支持 `Auto / 只回答 / 风险复核 / 代码调整` 模式
- 问答请求状态展示请求模式、实际模式、提示词、实际附带源码和取证轨迹
- 草稿层作为正式意图入口和后续生成的唯一依据
- 草稿内嵌实现建议生成
- 代码 diff 生成、单条/批量写回与结果回显

### LLM 能力

- 同时支持本地规则路径和远程 LLM 配置
- 通过受控 Agent Runtime 驱动问答、实现建议和代码 diff 流程
- 通过 Tool Facade 按需读取图节点、代码、草稿和验证结果
- 通过 Artifact 在问答、草稿、实现建议和代码阶段之间传递结构化结果
- 远程不可用时,自动回退到本地规则或模板结果,并明确标识来源


## 安装

Link Graph 当前提供两种安装方式。详细介绍见 [docs/installation.md](docs/installation.md)。

### 方式一:JetBrains Marketplace(准备中)

插件正在准备上架 JetBrains Marketplace。上架后,可以在 IDE 内 `Settings/Preferences -> Plugins -> Marketplace` 搜索 `Link Graph` 或插件 ID **`com.charmnight.linkgraph`** 直接安装。

### 方式二:从源码构建(当前推荐)

```bash
git clone https://github.com/CharmNight/link-graph.git
cd link-graph
npm --prefix web ci
./gradlew buildPlugin
```

构建产物为 `build/distributions/Link Graph-<version>.zip`,在 IDE 中通过 `Plugins -> 齿轮图标 -> Install Plugin from Disk...` 选择该 zip 即可。

调试或快速验证可以使用 `./gradlew runIde` 启动沙箱 IDE。详见 [docs/installation.md](docs/installation.md)。

## 快速上手

1. 安装前端依赖:`npm --prefix web ci`
2. 运行后端测试:`./gradlew test`
3. 启动插件沙箱:`./gradlew runIde`
4. 在沙箱 IDE 中打开一个 Java 或 Kotlin 项目
5. 通过编辑器右键菜单、`Tools` 菜单或右侧工具窗口打开 Link Graph

更详细的步骤见 [docs/getting-started.md](docs/getting-started.md)。功能操作说明见 [docs/usage.md](docs/usage.md)。

## 环境要求

- **JDK**:17(`gradle.properties` 的 `javaVersion` 固定)
- **Node.js**:`^20.19.0` 或 `>=22.12.0`(由 `web/package.json` 的 `engines` 声明)
- **IntelliJ IDEA**:2023.3.4 或更高兼容版本;最低平台 build 由 `gradle.properties` 的 `platformSinceBuild` 声明

## 工作台概览

Link Graph 的工作台包含五个区域:

- **顶部任务栏**:展示当前目标、五阶段流程、Mermaid 导入/导出、对比代码、同步预览、打开设置等全局动作。
- **左侧链路大纲**:从入口、关键路径、资源证据、风险线索和草稿影响定位当前链路。
- **中间图谱舞台**:展示六类图谱视图之一;图谱模式与右侧阶段互不绑定。
- **右侧阶段工作台**:按当前阶段展示讲解、证据、问答、草稿或代码 diff。
- **底部变更托盘**:汇总候选变更、已确认草稿、阻塞风险、diff 状态和写回入口。

图谱视图的阅读策略、各类视图的推荐用法、Mermaid 工作流和 LLM 流程细节,见 [docs/usage.md](docs/usage.md)。

架构图、类图和 Review Graph 是项目级架构索引上的范围投影,不表示把项目、三方库和 JDK 全量节点一次性铺满画布。

## LLM 能力说明

插件同时支持本地规则执行和远程 LLM 提供方。远程能力不是默认必需项,需要在插件设置中显式配置。远程执行未启用、配置不完整或调用失败时,部分流程会回退到本地规则或模板结果,而不会伪装成远程请求成功。

LLM 层的核心设计:

- **执行模型**:一次问答、实现建议生成或代码 diff 请求,会启动一次受控 Agent Run。Agent Run 在预算、停止条件和安全边界约束下多步执行,而不是无约束自由对话。
- **上下文获取**:Agent 不依赖一次性大 prompt 读取全部信息,而是通过 Tool Facade 按需读取图节点、代码片段、草稿状态和验证结果。
- **产品边界**:问答默认 `Auto`,后端识别实际模式;`只回答` 不推进候选变更,`风险复核` 不自动推进草稿,`代码调整` 才保留证据充分的候选变更;流程图意图优先于代码事实;草稿确认是正式意图入口;代码 diff 生成不等于写回。
- **回退路径**:远程模型不可用时,问答、实现建议和代码 diff 流程仍可回退到本地规则或模板,并明确标识来源。

当前与 LLM 相关的功能边界和限制见 [docs/features-and-limitations.md](docs/features-and-limitations.md)。

## 文档导航

| 主题 | 文档 |
|---|---|
| 安装方式 | [docs/installation.md](docs/installation.md) |
| 快速开始 | [docs/getting-started.md](docs/getting-started.md) |
| 使用说明 | [docs/usage.md](docs/usage.md) |
| 开发说明 | [docs/development.md](docs/development.md) |
| 架构说明 | [docs/architecture.md](docs/architecture.md) |
| 项目结构说明 | [docs/project-structure.md](docs/project-structure.md) |
| 功能与限制 | [docs/features-and-limitations.md](docs/features-and-limitations.md) |
| 变更日志 | [CHANGELOG.md](CHANGELOG.md) |

## 仓库结构

- `src/main/kotlin` — 插件后端实现
- `src/main/resources` — 插件描述与文案资源;构建时会合入生成的前端资源
- `src/test/kotlin` — 单元测试与平台测试
- `src/test/resources/fixtures` — 后端测试样例
- `src/integrationTest/kotlin` — 集成测试
- `web/` — React + Vite 前端工作区
- `docs/` — 公开文档
- `docs/diagrams/` — 文档中引用的架构图与 Mermaid 源文件

完整目录与各包职责说明见 [docs/project-structure.md](docs/project-structure.md)。

## 常用命令

```bash
./gradlew test               # 后端单元测试
./gradlew integrationTest    # 集成测试
./gradlew check              # 完整检查(含前端测试)
./gradlew runIde             # 启动带插件的沙箱 IDE
./gradlew buildPlugin        # 构建插件 zip 分发产物
npm --prefix web test        # 单独跑前端测试
```

## 参与贡献

欢迎通过 Issue 和 Pull Request 参与。开始前请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

- Bug 报告 / 功能建议:[GitHub Issues](https://github.com/CharmNight/link-graph/issues)
- 用法问题:先查阅 [SUPPORT.md](SUPPORT.md)
- 代码贡献:[CONTRIBUTING.md](CONTRIBUTING.md) + [Pull Request 模板](.github/PULL_REQUEST_TEMPLATE.md)

## 行为准则

参与本项目的每一位贡献者都需要遵守 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

## 安全披露

安全问题不要通过公开 Issue 报告。请按 [SECURITY.md](SECURITY.md) 的指引通过 GitHub Security Advisory 或维护者私下联系方式报告。

## 当前状态

仓库仍处于持续演进阶段。公开文档以当前实现和工作台语义为准,不承诺历史设计思路和当前实现完全一致。版本演进见 [CHANGELOG.md](CHANGELOG.md)。

## 许可证

本项目采用 MIT 许可证,详见 [LICENSE](LICENSE)。
