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

### 图视图性能 trace

需要真实拆分“后端解析慢、传输慢、前端布局慢”时，用 `LINKGRAPH_DEBUG_TRACE=true ./gradlew runIde` 启动沙箱 IDE。开启后，IDE 日志会包含 `渲染链路 trace` 和 `前端 trace`。

重点看这些阶段：

- `architectureGraph.buildIndex` / `classDiagram.buildIndex` / `reviewGraph.buildIndex`：项目级 JVM 符号与关系索引耗时。
- `classDiagram.buildStructureIndex` / `classDiagram.completeBuildIndex`：类图两阶段加载耗时。首次无完整索引时，类图会先构建结构快照，再异步补齐完整关系。
- `architectureIndex.sourceComponents` / `architectureIndex.cacheKey`：索引依赖的源码解析器、附加 Jar 指纹和 cache key 构造耗时。
- `jvmSymbolIndex.contentRoots` / `jvmSymbolIndex.allClassesSearch` / `jvmSymbolIndex.shortNamesCache`：项目文件扫描、IDE 类索引搜索和短名缓存遍历耗时。
- `jvmSymbolIndex.attachedJars` / `jvmSymbolIndex.externalLibraries` / `jvmSymbolIndex.jdk`：附加 Jar、外部库、JDK 类型扩展耗时。
- `architectureIndex.relation.<resolverId>`：单个 JVM 关系解析器耗时，例如调用关系、类型使用、反射、Spring Event、Framework 规则。
- `architectureIndex.relationIndex` / `architectureIndex.graphBuild`：关系索引汇总与架构图构建耗时。
- `architectureGraph.project` / `classDiagram.project` / `classDiagram.completeProject` / `reviewGraph.project`：后端把索引投影成视图文档的耗时与 visible/full 图规模。
- `reviewGraph.resolveDiff` / `reviewGraph.buildEvidence`：Review Graph 的 diff 解析、git/evidence/blast radius 构建耗时。
- `transport.bootstrap.payload` / `transport.payload.current` / `transport.renderScript`：后端序列化与 JCEF 注入 payload 耗时、脚本大小。
- `editorTransport.bootstrap.applied` / `bootstrapProjectionState.applied`：前端接收和合并 bootstrap state 耗时。
- `useMeasuredLayout.start` / `elkLayout.complete` / `useMeasuredLayout.complete`：前端 ReactFlow/ELK 布局耗时。
- `graphFlowSurface.renderCommitted`：ReactFlow surface 提交渲染耗时。

如果 `buildIndex` 首次很慢但后续同项目请求明显变快，通常是冷索引成本；如果类图先出现结构节点、稍后关系补齐，优先对比 `classDiagram.buildStructureIndex` 和 `classDiagram.completeBuildIndex`；如果 `elkLayout.complete` 对 Review Graph 或架构图持续偏高，优先看 visible 节点/边数量和布局策略；如果 `transport.renderScript` 或 payload 阶段偏高，优先看是否把大视图随无关状态更新反复发送。

## 改动文档时的要求

- 面向外部读者的文档必须放在 `docs/` 或根目录公开文件中
- 文档必须使用中文
- 不提交内部执行计划、个人工作记录或本机绝对路径
- 内部过程文档只保留在本地 `docs/internal/`，该目录不作为公开仓库内容
- 公开文档修改后，应确保文档一致性测试仍然通过

## 建议阅读顺序

1. [project-structure.md](project-structure.md)
2. [architecture.md](architecture.md)
3. [features-and-limitations.md](features-and-limitations.md)
4. [usage.md](usage.md)
5. [getting-started.md](getting-started.md)
