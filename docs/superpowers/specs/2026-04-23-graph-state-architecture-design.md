# Graph Editor State Architecture Refactor Design

日期：2026-04-23

状态：待评审

作者：Codex

## 1. 背景

当前图编辑器状态模型已经出现系统性语义漂移，问题不再是某个局部 mutation 写错，而是整套模型同时存在多份近似但不等价的“图真值”：

- `visibleGraph`
- `workingGraph`
- `referenceWorkingGraph`
- `referenceFactGraph`
- `designBaselineGraph`
- `factGraphView`
- `flowchartView`
- `resourceRelationView`

前端和后端又各自维护一份当前展示态与编辑态，导致以下三个已确认问题会不断复发：

1. 批量提交会把旧快照整块覆盖到新快照上。
2. 在 `FLOWCHART` 或 `RESOURCE_RELATION_VIEW` 下编辑后，切换视图可能看到过期数据。
3. 资源主体请求流程图时，实际降级策略与注释和用户心智不一致。

继续在现有模型上追加修补会让问题扩大，因为根因不在某个单点，而在以下四个架构缺口：

1. 图真值没有单一 canonical source。
2. `selection / anchor / layout` 没有 scene 边界。
3. projection 到 canonical graph 的映射不足以支撑结构编辑命令。
4. store 提交模型允许长流程基于旧快照做整块替换。

本次重构目标不是兼容修补，而是正式收敛模型，删除旧路径。

## 2. 目标

### 2.1 主目标

1. 把图编辑器收敛到单一可写图真值。
2. 把 `semantic fact`、`workspace base`、`workspace current`、`design baseline` 的语义边界定死。
3. 把工作区场景与 diff 场景彻底分离，所有 scene-sensitive UI 状态都按 scene 存储。
4. 用命令式 bridge 协议替代整图回写协议。
5. 引入只支持短事务的状态 store，消灭旧快照整块覆盖。
6. 把 flowchart / resource projection 的可编辑性规则显式化，不再依赖隐式猜测。
7. 清理旧字段、旧 mutation、旧测试预期，不保留兼容壳。

### 2.2 非目标

1. 本次不保留 dual-read / dual-write 兼容层。
2. 本次不保留旧 `GraphChanged(GraphDocument)` 协议别名。
3. 本次不保留旧 `visibleGraph / workingGraph / referenceWorkingGraph` 语义映射。
4. 本次不把长耗时语义分析、review、generation 包进串行 store。
5. 本次不尝试让所有投影元素都可编辑；没有可靠 canonical 映射的投影元素必须只读。

## 3. 现状问题闭环

### 3.1 旧快照覆盖新快照

当前批量修改通过旧快照创建草稿，再在结束时用整块 `replaceSnapshot(nextState)` 提交。前端桥接异步入口又不是天然串行，较早启动但较晚完成的任务会基于过期快照生成整块状态，并在结束时覆盖较新的状态。

根因不是“某个异步任务忘了加锁”，而是：

- 批处理以快照拷贝开局；
- 提交阶段不是字段级事务，而是整快照替换；
- revision 不匹配时仍直接接受 `nextState`；
- 异步任务计算与提交边界混在一起。

### 3.2 多视图状态不一致

当前前端结构编辑只把“当前展示视图”的整图发回后端。后端再以“当前视图编辑”方式更新局部 view，并保留其他 view 文档。这意味着：

- `FLOWCHART` 编辑会把 flowchart 当前图写回；
- `FACT_GRAPH` 与 `RESOURCE_RELATION_VIEW` 继续保留旧文档；
- 切视图时用户看到的可能不是同一份 workspace 的不同投影，而是三份互相漂移的文档。

### 3.3 资源主体展示策略不一致

当前资源主体请求 `FLOWCHART` 时，注释说明应降级到资源关系视图，但实现实际返回 `FACT_GRAPH`。这不是单纯注释错误，因为它会直接影响：

- 初始展示模式；
- 切换视图后的投影选择；
- 用户对“当前主体是什么”的理解。

### 3.4 Scene-unaware UI 状态

当前 `selectedNodeId`、`anchorNodeId`、`layoutState` 只保留全局单份。diff 只是在图层上额外做了区分，没有对 UI 状态做同级隔离，因此：

- 工作区和 diff 场景会共享选中节点；
- 不同视图会共享 anchor；
- 布局在 scene 间串味；
- 前端本地的分组选择、折叠状态、diff 目标状态也会串味。

### 3.5 Projection 映射能力不足

当前只有“选中节点”会尝试从 flowchart 投影节点回映射到原始节点。这不足以支撑结构编辑命令：

- `DeleteNodeSubtree`
- `InsertNodeIntoEdge`
- `ConnectNodes`
- `UpdateNode`

尤其 flowchart 可读投影中存在：

- alias 合并节点；
- synthetic 边；
- path 压缩；
- overflow 节点；

这些都不能再被当成天然可编辑的真实元素。

## 4. 核心语义与不变量

### 4.1 四类图语义

#### `semanticFactGraph`

定义：

- 最近一次统一语义分析直接产出的事实图。
- 只代表事实和语义提取结果。
- 不承载用户编辑、草稿确认、布局、diff 或投影裁剪。

约束：

- 只有重新语义分析成功时才能整体替换。
- 前端结构编辑绝不能修改它。
- 它是问答、讲解、证据收集的重要背景图，但不是当前工作区真值。

#### `workspaceBaseGraph`

定义：

- 当前工作区的 canonical 可编辑基线。
- 语义分析完成后，从 `semanticFactGraph` 派生出新的工作区基线。
- 草稿重放、手工编辑、前端结构命令都不会直接改它。

约束：

- 它是“当前 workspace 编辑是基于哪一版事实图展开”的边界。
- 重新语义分析时生成新版本。
- 当存在本地编辑时，新基线与当前工作图的差异必须通过显式重放或冲突决议处理，不能隐式覆盖。

#### `workspaceGraph`

定义：

- 唯一可写 canonical graph。
- 所有结构编辑和草稿确认都只写这份图。

约束：

- 前端结构命令、草稿确认/取消、Mermaid 作为工作区导入，都只改 `workspaceGraph`。
- 三个工作区视图只能由它投影出来。
- 任意结构变更之后，所有 workspace view 必须一起重建。

#### `designBaselineGraph`

定义：

- 用于代码设计基线和 diff 的独立参考图。
- 不属于 workspace 真值链。

约束：

- 只参与 diff 和同步预览。
- 不能作为工作区编辑真值的回退来源。

### 4.2 Scene 模型

引入明确的 scene 枚举，不再使用 `diffMode: Boolean`：

- `WORKSPACE_FACT`
- `WORKSPACE_FLOWCHART`
- `WORKSPACE_RESOURCE_RELATION`
- `DIFF`

约束：

1. 工作区场景只表示同一份 `workspaceGraph` 的不同投影。
2. `DIFF` 是独立场景，不允许污染工作区场景状态。
3. 当前场景切换只改变当前展示和当前 scene UI state，不改变 canonical graph。

### 4.3 Scene-aware UI 状态

以下状态必须按 scene 持有，禁止全局单份：

- `selectedNodeId`
- `anchorNodeId`
- `layoutState`
- `collapsedNodeIds`
- `detailNodeId`
- `selectionGroupNodeIds`
- `auditTargetNodeIds`
- `diffTargetItemIds`

状态划分：

- 后端持久 scene state：
  - `selectedNodeId`
  - `anchorNodeId`
  - `layoutState`
  - `collapsedNodeIds`
- 前端局部 scene state：
  - `detailNodeId`
  - `selectionGroupNodeIds`
  - `auditTargetNodeIds`
  - `diffTargetItemIds`

额外约束：

1. layout 不能再依赖 node metadata 里的 `ui.x / ui.y` 做跨 scene 存储。
2. scene 切换时只切换 scene 自己的 UI 状态。
3. diff scene 使用独立的 layout 和 selection，不回写工作区 scene。

### 4.4 Projection Bundle 与 Projection Index

每个 workspace scene 的 view 文档必须同时输出：

1. `visibleGraph`
2. `fullGraph`
3. `anchorNodeId`
4. `summary`
5. `projectionIndex`

`projectionIndex` 的职责不是“展示辅助”，而是编辑协议的硬约束。它至少需要回答：

- 一个投影节点对应哪个 canonical node 或哪些 canonical nodes。
- 一个投影边对应哪个 canonical edge、哪段 canonical path，或明确不可编辑。
- 该投影元素是：
  - `EXACT`
  - `MERGED_ALIAS`
  - `PATH_ALIAS`
  - `SYNTHETIC_READONLY`
  - `OVERFLOW_READONLY`

命令执行规则：

1. `EXACT` 才能无歧义执行全部结构命令。
2. `MERGED_ALIAS` 只允许支持“唯一 canonical root”语义的命令；若命令目标不唯一，必须拒绝。
3. `PATH_ALIAS` 只能执行显式定义了 path insert / split 规则的命令。
4. `SYNTHETIC_READONLY` 与 `OVERFLOW_READONLY` 只能选中、定位、讲解，不能改图。

### 4.5 无兼容层原则

本次重构必须遵守以下清理约束：

1. 不保留旧字段别名。
2. 不保留旧消息别名。
3. 不保留旧 mutation facade 的影子实现。
4. 不保留“先写新字段，再同步旧字段”的过渡层。
5. 不保留“新旧 schema 同时可读”的长期兼容壳。

## 5. 目标状态模型

目标状态拆成四层：

1. workspace 层
2. projection 层
3. scene 层
4. workbench / request 层

### 5.1 Workspace 层

```kotlin
data class GraphWorkspaceState(
    val semanticFactGraph: GraphDocument,
    val workspaceBaseGraph: GraphDocument,
    val workspaceGraph: GraphDocument,
    val designBaselineGraph: GraphDocument? = null,
    val workspaceRevision: Long,
    val semanticRevision: Long,
)
```

语义：

- `semanticFactGraph` 是最近一次分析事实图。
- `workspaceBaseGraph` 是当前编辑基线。
- `workspaceGraph` 是当前可写工作图。

### 5.2 Projection 层

```kotlin
data class GraphProjectionBundle(
    val factView: FactGraphSceneDocument,
    val flowchartView: FlowchartSceneDocument,
    val resourceRelationView: ResourceRelationSceneDocument,
)
```

要求：

- 全部由 `workspaceGraph` 纯函数投影生成。
- 不能被单独 mutation。
- projection 文档中必须携带 `projectionIndex`。

### 5.3 Scene 层

```kotlin
enum class GraphSceneId {
    WORKSPACE_FACT,
    WORKSPACE_FLOWCHART,
    WORKSPACE_RESOURCE_RELATION,
    DIFF,
}

data class GraphSceneState(
    val selectedNodeId: String? = null,
    val anchorNodeId: String? = null,
    val layoutState: GraphLayoutState = GraphLayoutState(),
    val collapsedNodeIds: Set<String> = emptySet(),
)
```

要求：

- `currentSceneId` 只决定当前展示哪个 scene。
- `sceneStates` 以 `GraphSceneId -> GraphSceneState` 持有。
- `DIFF` 使用独立 scene state。

### 5.4 Workbench / Request 层

workbench、async request、draft validation、generated code draft 等继续存在，但它们不再决定图真值来源。它们只能引用：

- `workspaceGraph`
- `semanticFactGraph`
- `designBaselineGraph`
- 当前 scene

## 6. 状态存储与事务模型

### 6.1 新 Store

引入新的 `GraphEditorStateStore`，替代现有：

- `GraphEditorStateSyncSession`
- `newDraftMutationContext`
- `replaceSnapshot(nextState)`

设计要求：

1. store 只能执行短事务。
2. 提交必须基于 revision compare-and-set。
3. transaction 内不能做读锁外长耗时计算。
4. transaction 结果必须是字段级受控更新，不允许整块过期快照替换。

### 6.2 建议接口

```kotlin
interface GraphEditorStateStore {
    fun snapshot(): GraphEditorState
    fun mutate(transform: (GraphEditorState) -> GraphEditorState): GraphEditorState
    fun tryCommit(
        expectedRevision: Long,
        transform: (GraphEditorState) -> GraphEditorState,
    ): CommitResult
}
```

### 6.3 长耗时流程边界

所有长流程必须采用：

1. 读取只读快照
2. 在 store 外计算结果
3. 基于 `expectedRevision` 尝试提交
4. 若 revision 不匹配，按流程类型选择重算或放弃

适用流程：

- 语义分析
- 图问答
- 图讲解
- draft rebuild
- code generation
- diff review

### 6.4 Revision 规则

至少保留三类 revision：

- `storeRevision`
- `workspaceRevision`
- `layoutRevisionByScene`

约束：

1. 结构编辑推进 `storeRevision + workspaceRevision`。
2. 纯 layout 变更只推进当前 scene 的 layout revision 与 `storeRevision`。
3. 异步结果提交必须校验 `expected storeRevision` 或 `expected workspaceRevision`，按场景选用。

## 7. 工作流重构

### 7.1 统一图加载与语义分析

语义分析成功后，应按以下顺序更新：

1. 生成新的 `semanticFactGraph`
2. 生成新的 `workspaceBaseGraph`
3. 根据是否存在本地编辑决定 `workspaceGraph`
   - 无本地编辑：`workspaceGraph = workspaceBaseGraph`
   - 有本地编辑：把本地变更重放到新 `workspaceBaseGraph`
4. 从新 `workspaceGraph` 重建全部 workspace projection
5. 根据 display policy 选择默认 `currentSceneId`

### 7.2 前端结构编辑

前端不再发送整张当前图，而是发送命令：

- `AddNode`
- `UpdateNode`
- `DeleteNode`
- `DeleteNodeSubtree`
- `ConnectNodes`
- `DeleteEdge`
- `InsertNodeIntoEdge`
- `MoveNodesLayout`
- `FormatSceneLayout`

命令请求至少携带：

- `sceneId`
- `baseWorkspaceRevision`
- `projectionRef`

后端流程：

1. 根据 `sceneId` 和 `projectionRef` 解析 canonical target
2. 在 `workspaceGraph` 上执行命令
3. 重建所有 workspace views
4. 返回新 snapshot

### 7.3 前端布局编辑

布局可保留局部乐观更新，但语义必须改变：

1. 布局只改当前 `sceneId` 的 `layoutState`。
2. 布局更新不能改 `workspaceGraph` 语义结构。
3. 不再把 `ui.x / ui.y` 写回 canonical graph metadata，避免 scene 串味。

### 7.4 Draft 确认与取消

草稿确认和取消不再操作“旧 workingGraph 近似概念”，而是：

1. 从 `workspaceBaseGraph` 或当前明确基线计算 draft apply 结果；
2. 在 store 外完成 rebuild；
3. 提交到 `workspaceGraph`；
4. 重建 projection；
5. 更新 draft workbench 状态。

### 7.5 Diff 场景

diff 结果不再复用 workspace scene：

1. `DIFF` 是独立 scene。
2. diff 视图数据、layout、selection 独立持有。
3. 进入 diff 不会覆盖 workspace scene state。
4. 退出 diff 恢复上一次 workspace scene。

### 7.6 资源主体展示策略

正式策略：

1. 资源主体默认 scene 是 `WORKSPACE_RESOURCE_RELATION`。
2. 资源主体请求 `FLOWCHART` 时，统一降级到 `WORKSPACE_RESOURCE_RELATION`。
3. 不再降级到 `FACT_GRAPH`。

## 8. Projection 与编辑协议设计

### 8.1 节点映射

每个可见节点都必须暴露：

- `projectedNodeId`
- `mappingKind`
- `canonicalNodeIds`
- `editableCommandKinds`

示例：

- fact view 普通节点：`EXACT -> [node-1]`
- flowchart 合并节点：`MERGED_ALIAS -> [guard-node, condition-node]`
- overflow 节点：`OVERFLOW_READONLY -> []`

### 8.2 边映射

每条可见边都必须暴露：

- `projectedEdgeId`
- `mappingKind`
- `canonicalEdgeIds`
- `canonicalPath`
- `insertSite`

`InsertNodeIntoEdge` 只在以下条件成立时允许：

1. 对应唯一 canonical edge；
2. 或对应显式定义的唯一 insert site。

否则拒绝执行。

### 8.3 命令拒绝原则

以下情况必须直接拒绝命令，而不是猜测修复：

1. 投影目标对应多个 canonical roots。
2. 目标元素是 synthetic / overflow。
3. path alias 没有唯一 insert / delete 规则。
4. 当前 scene 不允许该命令。

用户反馈必须说明：

- 为什么当前元素不可编辑；
- 应切换到哪个 scene 编辑。

## 9. Bridge 与 Transport 重构

### 9.1 删除旧协议

必须删除：

- `GraphEditorMessage.GraphChanged(GraphDocument)`
- 前端 `publishGraphChange(nodes, edges)` 整图回写
- 后端 `handleFrontendGraphChanged(graph)`
- `markViewGraphChanged`

### 9.2 新协议

结构编辑桥接协议按命令拆分。每条命令都应具有稳定 schema，不再复用“回传整图”模式。

传输约束：

1. 前端不再自行决定 canonical graph 内容。
2. 后端负责 canonical command apply。
3. 前端收到的是新的 authoritative snapshot。

### 9.3 Snapshot 传输

bootstrap / incremental transport 可以继续发送整份 authoritative snapshot，但 payload 结构必须换成新模型：

- `workspace`
- `projections`
- `sceneStates`
- `currentSceneId`
- `workbench`

旧字段：

- `visibleGraph`
- `workingGraph`
- `referenceWorkingGraph`
- `referenceFactGraph`

必须删除，不保留兼容输出。

## 10. 文件级落点

### 10.1 后端状态内核

必须重做或删除的核心文件：

- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateModels.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorSnapshot.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorSnapshotMutations.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorInteractionMutations.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt`
- `src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorStateSyncSession.kt`
- `src/main/kotlin/com/charmnight/linkgraph/services/ProjectEditorSession.kt`

### 10.2 后端工作流

必须按新语义改造的消费链：

- `src/main/kotlin/com/charmnight/linkgraph/services/GraphWorkspaceWorkflow.kt`
- `src/main/kotlin/com/charmnight/linkgraph/services/SubjectGraphWorkflow.kt`
- `src/main/kotlin/com/charmnight/linkgraph/services/PlanningContextFactory.kt`
- `src/main/kotlin/com/charmnight/linkgraph/services/ConfirmedDraftChangeWorkflow.kt`
- `src/main/kotlin/com/charmnight/linkgraph/services/ConfirmedDraftChangeSyncWorkflow.kt`
- `src/main/kotlin/com/charmnight/linkgraph/services/AsyncRequestLifecycleSupport.kt`

### 10.3 Projection 与视图文档

必须重做或收敛的文件：

- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorViewSupport.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/view/ReadableFlowchartProjection.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/view/FactGraphProjector.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/view/FlowchartProjector.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/view/ResourceRelationProjector.kt`
- `src/main/kotlin/com/charmnight/linkgraph/services/InteractiveGraphProjector.kt`

### 10.4 Bridge 与渲染

必须改协议的文件：

- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorMessage.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserBridgeRegistrar.kt`
- `src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorCommandRouter.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRenderer.kt`
- `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorTransportSliceRenderer.kt`

### 10.5 前端状态与交互

必须重构的文件：

- `web/src/app/types.ts`
- `web/src/app/sampleState.ts`
- `web/src/app/App.tsx`
- `web/src/app/controllers/useWorkbenchState.ts`
- `web/src/app/controllers/useBootstrapProjectionState.ts`
- `web/src/app/controllers/useGraphEditController.ts`
- `web/src/app/controllers/useGraphCanvasController.ts`
- `web/src/app/controllers/useWorkbenchDerivedState.ts`
- `web/src/app/api.ts`
- `web/src/app/graphState.ts`
- `web/src/app/layoutEditability.ts`
- `web/src/app/workingGraphDocument.ts`

### 10.6 必删旧入口

实现完成后必须删除：

- `markViewGraphChanged`
- `resolveWorkingGraphDocument`
- `publishGraphChange`
- 旧 `visibleGraph / workingGraph / referenceWorkingGraph / referenceFactGraph` 读取路径
- `diffMode: Boolean`

## 11. 测试基线重建

开始实现前，必须先改掉锁定旧错误行为的测试。

### 11.1 必须删除或重写的旧预期

- `src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateServiceTest.kt`
  - `markViewGraphChanged在流程图模式下更新流程图文档并保留其他视图`
- `src/test/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectServiceSemanticAnalysisTest.kt`
  - 资源主体 `FLOWCHART -> FACT_GRAPH` 的旧预期
- `src/test/kotlin/com/charmnight/linkgraph/services/GraphEditorStateSyncSessionTest.kt`
  - 旧 batch session 语义测试
- `web/src/test/app/workingGraphDocument.test.ts`
  - 旧 working graph 回退规则测试

### 11.2 新测试焦点

新测试必须围绕新不变量，不再围绕旧字段行为。

## 12. 回归测试矩阵

| 主题 | 场景 | 断言 |
| --- | --- | --- |
| Store 并发 | 两个异步任务基于不同 revision 提交 | 旧 revision 提交失败，不能覆盖新 `workspaceGraph` |
| Store 事务 | 语义分析 / review / generation 都先算后提 | store 内无长事务，提交只做短 CAS |
| 图真值 | 任意结构编辑 | 只修改 `workspaceGraph` |
| 多视图一致性 | 在 `WORKSPACE_FLOWCHART` 编辑后切到 `WORKSPACE_FACT` / `WORKSPACE_RESOURCE_RELATION` | 三个 view 都反映同一 canonical 变更 |
| Scene 隔离 | workspace scene 与 diff scene 来回切换 | selection / anchor / layout / collapsed 不串味 |
| Layout 隔离 | 同一节点在 FACT 与 FLOWCHART 下调整布局 | 两个 scene 位置互不覆盖 |
| Projection 映射 | 选中 flowchart alias 节点后执行删除子树 | 只有存在唯一 canonical root 才允许执行 |
| Projection 拒绝 | 对 synthetic edge 执行 `InsertNodeIntoEdge` | 命令被拒绝，并返回用户可理解原因 |
| 资源主体展示 | 资源主体请求 `FLOWCHART` | 实际 current scene 为 `WORKSPACE_RESOURCE_RELATION` |
| Diff 隔离 | 进入 diff 场景后拖拽、选择、退出 | diff scene 状态不回写 workspace scene |
| Draft 重建 | 有本地编辑时重新语义分析 | 本地编辑重放到新 `workspaceBaseGraph`，不会被整块覆盖 |
| 问答/讲解 | 需要事实背景但工作区有本地编辑 | `semanticFactGraph` 与 `workspaceGraph` 使用边界正确 |
| Bridge 协议 | 前端结构编辑 | 不再发送整图 `GraphChanged` |
| ID 分配 | 新增手工节点 | 节点 ID 由后端分配，前端不保留 canonical counter 真值 |
| 导入与 diff | 导入设计基线并进入 diff | `designBaselineGraph` 只参与 diff，不参与 workspace 真值回退 |
| 页面 bootstrap | 初始加载与增量同步 | payload 只包含新模型字段，不输出旧字段 |

## 13. 实施顺序约束

实现顺序必须固定，避免旧测试和旧协议反向卡住重构：

1. 先改不变量与测试基线。
2. 再改状态内核与 scene 模型。
3. 再改 projection / mapping 契约。
4. 再改后端 workflow。
5. 再改 transport / bridge。
6. 最后改前端状态与交互，并删除死代码。

## 14. 最终结论

本次问题不能通过“给旧快照提交再补一个 revision 判断”或“当前视图编辑后顺手刷新其他 view”解决。正确做法是正式改成：

1. 单一可写 `workspaceGraph`
2. 明确定义的 `semanticFactGraph` 与 `workspaceBaseGraph`
3. scene-aware UI state
4. 携带 `projectionIndex` 的投影文档
5. 命令式 bridge 协议
6. 短事务 `GraphEditorStateStore`

只有这样，才能一次性清掉旧快照覆盖、多视图漂移、scene 串味和资源主体展示策略不一致这几类问题。
