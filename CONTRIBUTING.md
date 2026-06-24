# 贡献指南

谢谢你想为 Link Graph 出力。这份指南覆盖开发环境、常用命令、代码规范、commit 与分支约定以及 PR 流程。

开始之前,请先阅读 [README.md](README.md) 和 [docs/architecture.md](docs/architecture.md),对项目定位和当前架构有整体认识。

参与本项目需要遵守 [行为准则](CODE_OF_CONDUCT.md)。

## 开发环境

- **JDK**:17(`gradle.properties` 的 `javaVersion` 固定)
- **Node.js**:`^20.19.0` 或 `>=22.12.0`(由 `web/package.json` 的 `engines` 声明)
- **IntelliJ IDEA**:2023.3.4 或更高兼容版本
- 推荐:使用 IntelliJ IDEA 作为主 IDE,以便直接调试 Kotlin 插件代码

CI 在 Ubuntu 上使用 JDK 17 和 Node 22,和本地推荐保持一致。CI 配置见 [`.github/workflows/ci.yml`](.github/workflows/ci.yml)。

## 初始化

```bash
git clone https://github.com/CharmNight/link-graph.git
cd link-graph
npm --prefix web ci
./gradlew test
```

前端依赖必须先安装,否则 `./gradlew check` 会因为 `frontendInstall` 失败而中断。

## 常用命令

### 后端

```bash
./gradlew test               # 后端单元测试
./gradlew integrationTest    # 集成测试
./gradlew check              # 完整检查(含前端测试)
./gradlew runIde             # 启动带插件的沙箱 IDE
./gradlew buildPlugin        # 构建插件 zip 分发产物
```

### 前端

```bash
npm --prefix web ci          # 安装依赖
npm --prefix web test        # 运行测试(vitest)
npm --prefix web run build   # 构建前端产物
```

### 全量检查

`./gradlew check` 会执行后端检查、集成测试和前端测试,等价于 CI 的主流程。提交 PR 前建议本地跑一次。

## 代码规范

### Kotlin

- 遵循 [Kotlin 官方代码风格](https://kotlinlang.org/docs/coding-conventions.html)(`gradle.properties` 已声明 `kotlin.code.style = official`)。
- 公共 API 优先使用 KDoc 注释,说明意图、参数语义和返回值边界。
- 新增逻辑必须有对应测试。测试位置见 [docs/development.md](docs/development.md)。

### TypeScript / React

- 遵循 `web/src/app` 下现有风格。
- 业务代码只放在 `web/src/app`,测试只放在 `web/src/test/app`,两者镜像对应。
- 新增测试一律放 `web/src/test/app`,不要回写到 `web/src/app`。
- 严禁把 `*.test.ts` / `*.test.tsx` 放回 `web/src/app`。

### 测试

- 新增/修改后端逻辑:补 `src/test/kotlin` 或 `src/integrationTest/kotlin` 对应测试。
- 新增/修改前端逻辑:补 `web/src/test/app` 对应测试。
- 修复 bug 时,先写一个能复现该 bug 的测试,再提交修复。

### 文档

- 面向外部读者的文档必须放在 `docs/` 或根目录公开文件中。
- 文档必须使用中文。
- 不提交内部执行计划、个人工作记录或本机绝对路径。
- 内部过程文档只保留在本地 `docs/internal/`,该目录已由 `.gitignore` 忽略。
- 公开文档修改后,应确保文档一致性测试仍然通过。
- 如果某个流程依赖本地规则回退、模板结果或部分实现,文档必须明确写出,不能暗示其已经是完整的远程自动化能力。

## Commit 规范

遵循简版 [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body>
```

常用 type:

- `feat`: 新功能
- `fix`: bug 修复
- `refactor`: 重构(不改对外行为)
- `docs`: 文档
- `test`: 测试补充或修正
- `chore`: 构建、CI、依赖等杂项
- `perf`: 性能优化

scope 可选,例如 `class-diagram`、`architecture`、`ui`、`llm`。

示例:

```
feat(class-diagram): 支持类图拖拽定位后重算连线
fix(ui): 避免打开 IDE UI 时阻塞 JCEF bridge
docs: 补充 Node 版本要求
```

## 分支命名

- `feat/<short-description>` — 新功能
- `fix/<short-description>` — bug 修复
- `refactor/<short-description>` — 重构
- `docs/<short-description>` — 文档

分支名使用英文小写和中划线分隔,避免包含 issue 编号或日期前缀。

## PR 流程

1. 先在 Issue 中讨论改动方向(尤其是新功能和破坏性变更)。
2. 从最新 `master` 拉分支。
3. 按 [Pull Request 模板](.github/PULL_REQUEST_TEMPLATE.md) 填写摘要、关联 Issue、改动类型、验证命令和文档更新。
4. 本地执行 `./gradlew check` 确认全部通过。
5. 提交 PR,等待 review。

### PR 自查清单

- [ ] commit message 符合规范
- [ ] 新增/修改逻辑有对应测试
- [ ] `./gradlew check` 全部通过
- [ ] 公开文档已同步(如有行为变更)
- [ ] CHANGELOG.md 的 `Unreleased` 段已补充(如面向用户的行为变更)
- [ ] 不包含本机路径、敏感信息、生成产物
- [ ] 不包含 `docs/internal/` 内容(已被 gitignore)

## 报告问题

- Bug / 功能建议:通过 [GitHub Issues](https://github.com/CharmNight/link-graph/issues),使用对应模板。
- 用法问题:先查阅 [SUPPORT.md](SUPPORT.md) 和 [docs/usage.md](docs/usage.md)。
- 安全问题:按 [SECURITY.md](SECURITY.md) 私下报告,**不要**通过公开 Issue。

## 仓库结构

完整目录与各包职责说明见 [docs/project-structure.md](docs/project-structure.md)。前端调试入口和 trace 用法见 [docs/development.md](docs/development.md)。

## 建议阅读顺序

1. [README.md](README.md)
2. [docs/project-structure.md](docs/project-structure.md)
3. [docs/architecture.md](docs/architecture.md)
4. [docs/features-and-limitations.md](docs/features-and-limitations.md)
5. [docs/usage.md](docs/usage.md)
6. [docs/getting-started.md](docs/getting-started.md)
