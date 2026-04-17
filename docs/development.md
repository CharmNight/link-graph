# 开发说明

## 开发模式概览

这个项目同时包含 IntelliJ 插件后端和内嵌前端。日常开发通常分成两条链路：

- 后端链路
  - 修改 Kotlin 代码、测试、插件入口和工具窗口逻辑
- 前端链路
  - 修改 `web/` 下的 React 工作台、图渲染和交互控制逻辑

## 常用命令

### 后端

```bash
./gradlew test
./gradlew integrationTest
./gradlew runIde
```

### 前端

```bash
npm --prefix web ci
npm --prefix web test
npm --prefix web run build
```

### 全量检查

```bash
./gradlew check
```

`check` 会执行后端检查，并接线触发前端测试。

## 构建与打包

前端相关任务由 `build.gradle.kts` 统一接线：

- `frontendInstall`
- `frontendTest`
- `frontendBuild`
- `frontendPackResources`

正常打包链路会把 `web/` 构建产物复制到生成资源目录，再进入插件资源。运行时优先读取打包资源，在开发或测试场景下可以回退到 `web/dist`。

## 调试建议

### 调试后端

- 直接使用 `./gradlew runIde` 启动沙箱 IDE
- 在插件代码中下断点，观察工具窗口创建、主体分析、桥接消息和状态更新

### 调试前端

- 前端源码位于 `web/src/app`
- 前端测试位于 `web/src/test`
- 重点入口通常是：
  - `App.tsx`
  - `controllers/`
  - `views/`
  - `reactflow/`
- 前端最终运行在 JCEF 环境中，因此要特别关注 bootstrap、bridge 和资源加载问题

前端测试目录采用镜像结构：

- `web/src/app/**`
  - 业务代码与运行时代码
- `web/src/test/app/**`
  - 对应 `web/src/app/**` 的单元测试与组件测试
- `web/src/test/setup.ts`
  - Vitest 全局初始化

新增前端测试时，不要把 `*.test.ts` 或 `*.test.tsx` 继续放回 `web/src/app`。

## 改动文档时的要求

- 面向外部读者的文档必须放在 `docs/` 或根目录公开文件中
- 文档必须使用中文
- 不提交内部执行计划、个人工作记录或本机绝对路径
- 公开文档修改后，应确保文档一致性测试仍然通过

## 建议阅读顺序

1. [project-structure.md](project-structure.md)
2. [architecture.md](architecture.md)
3. [features-and-limitations.md](features-and-limitations.md)
