# 贡献指南

## 开发环境

- JDK 17
- Node.js 与 npm
- 与 `gradle.properties` 中目标平台兼容的 IntelliJ IDEA

## 初始化

```bash
npm --prefix web ci
./gradlew test
```

## 常用流程

- 运行后端测试：`./gradlew test`
- 运行集成测试：`./gradlew integrationTest`
- 运行完整检查：`./gradlew check`
- 启动插件沙箱 IDE：`./gradlew runIde`
- 单独运行前端测试：`npm --prefix web test`

## 代码与文档要求

- 变更范围应尽量聚焦在当前任务。
- 当仓库结构或公开支持的工作流发生变化时，需同步更新公开文档。
- 不要提交本地 IDE 状态、沙箱输出、生成后的前端构建产物或机器相关路径。
- 如果某个流程依赖本地规则回退、模板结果或部分实现，文档必须明确写出，不能暗示其已经是完整的远程自动化能力。

## Pull Request 说明

- 说明问题背景和改动摘要。
- 列出本次实际执行的验证命令。
- 标明仍然留在范围外的限制、风险或后续工作。
