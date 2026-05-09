# Link Graph 架构边界重构设计

## 目标

通过一次破坏性、覆盖完整链路的重构，修复当前架构边界问题。这个方案不使用兼容包装层，也不做单点补丁。

本次重构必须一起解决以下五类问题：

1. `llm` 反向依赖 `services`。
2. workflow 直接突变 UI 状态。
3. confirmed-draft workflow 的拆分模式没有推广到其他流程，职责边界不一致。
4. command、router、project-service 层存在 legacy 转发链路。
5. semantic code provider 与 `CodeSubjectKind` 分支和负向匹配耦合。

## 非目标

- 不保留 `LinkGraphProjectService` 兼容 facade。
- 不增加一个旧 API 调新 API 的 bridge 兼容层。
- 不保留生产代码中的临时双入口命令路径。
- 不做大范围 UI 视觉重设计。
- 除了更清晰的状态和错误处理需要外，不主动改变用户可见行为。

## 覆盖矩阵

| 原问题 | 必须落地的结构性修复 | 验证门禁 |
| --- | --- | --- |
| 4.1 `llm -> services` 反向依赖 | 将 debug/env 和跨层中立 helper 下沉到 `foundation`；将 planning payload 迁移为纯 application/LLM 输入模型；按归属层拆分 diagnostics；从 LLM tools 中移除 service graph helper。 | 源码扫描禁止任何 `src/main/kotlin/.../llm/**` import `services` 或 `ui`；LLM 输入模型不得引用 `GraphEditorStateSnapshot`。 |
| 4.2 workflow 突变 UI 状态 | 将 workflow 行为转换为 application use case，返回明确 result/event；UI mutation 移到 presenter/reducer。 | 源码扫描禁止在允许的 UI presenter/reducer/session internals 之外出现 `session.mutate`、`session.mutateBatch`、`GraphEditorStateMutationContext`。 |
| 4.3 confirmed-draft 拆分不一致 | 将 confirmed-draft 的模式推广到所有流程：use case + presenter + 命名清晰的副作用 writer；删除 `SyncWorkflow` 这种混合职责命名。 | confirmed draft、draft patch、generation、QA、subject/workspace、navigation 都必须同时具备纯 use-case result 测试和独立 presenter mapping 测试。 |
| 4.4 router/facade 转发 | 删除 `LinkGraphProjectService`；除非 thin command stack 具备真实 IntelliJ service 注册价值或领域边界价值，否则删除；router 直接调用 application API。 | 生产源码不得包含 `LinkGraphProjectService`；router 不得依赖 legacy command forwarding stack。 |
| 4.5 provider kind 耦合 | 用精确 `supportedKinds` 注册替代负向 `supports()` 判断和 hardcoded code-provider `when` 分支。 | 重复或缺失 `CodeSubjectKind` 注册时测试失败；源码扫描禁止 `kind != CodeSubjectKind.JAVA_METHOD`。 |

## 当前根因

当前 `services` 包承担了过多职责。它同时包含 application workflow、UI 状态突变 helper、debug 工具、planning payload、project service 入口以及共享 diagnostics。这导致依赖方向同时向上和向下穿透：

- `services -> llm`：用于 generation、QA、runtime、parser。
- `llm -> services`：用于 debug environment、diagnostics、planning payload、graph snapshot helper。
- `services -> ui`：通过 `ProjectEditorSession`、`GraphEditorStateMutationContext` 和直接 workbench mutation 调用。
- `ui -> services`：通过 bridge、router、command services。

因为 package 边界没有明确语义，只修某一个 import 只会把违规移动到另一个文件。这个设计必须建立可执行的依赖方向，并让违规依赖通过测试失败暴露。

## 目标依赖方向

生产代码必须遵守以下依赖方向：

```text
actions/toolwindow/ui
  -> application
  -> domain modules + llm + codegen + semantic + sync + navigation + settings
  -> model/foundation
```

允许的依赖规则：

- `llm` 可以依赖 `model`、`workbench` 领域模型、settings runtime state 和 `foundation`。
- `llm` 不得依赖 `services`、`ui`、`toolwindow`、`actions`。
- `application` 可以编排 `llm`、`semantic`、`sync`、`codegen`、`navigation`、`settings` 和领域服务。
- `application` 不得直接突变 `GraphEditorStateService`。
- `ui` 拥有渲染、bridge payload、state reducer、browser sync。
- `actions` 和 `toolwindow` 必须通过 application command/use-case API 进入系统。
- `services` 包要么删除，要么缩小为 IntelliJ service registration adapter；它不得再包含业务 workflow。

## 包结构

### `foundation`

职责：提供任何层都可以安全依赖的共享基础设施。

迁移或新增：

- `LinkGraphDebugEnvironment`
- trace flag helper
- 不感知 UI state 的小型 diagnostic formatting primitive
- 必要时提供通用 `OperationFailure`、`UseCaseError`、result helper

规则：

- 不依赖 `services`、`ui`、`llm`、`application`，也不依赖 IntelliJ project service，除非该 helper 明确是 platform infrastructure。
- debug helper 在 runtime 测试需要确定性输入时必须可注入。

### `application`

职责：项目级业务编排。

新增：

- `GraphEditorApplicationService`
- use cases：
  - `RequestSubjectGraphUseCase`
  - `SwitchAnalysisDisplayModeUseCase`
  - `LoadWorkspaceGraphUseCase`
  - `ImportMermaidUseCase`
  - `ExportMermaidUseCase`
  - `ShowDiffModeUseCase`
  - `RequestSyncPreviewUseCase`
  - `RequestAuditUseCase`
  - `RetryAuditUseCase`
  - `ResolveInvestigationThreadUseCase`
  - `ConfirmDraftChangeUseCase`
  - `UnconfirmDraftChangeUseCase`
  - `RequestDiffReviewUseCase`
  - `RequestGraphBeautificationUseCase`
  - `PreviewDraftPatchUseCase`
  - `ApplyDraftPatchUseCase`
  - `RestoreDraftPatchPreviewUseCase`
  - `UndoDraftPatchApplyUseCase`
  - `GeneratePlanUseCase`
  - `DiscussPlanUseCase`
  - `GenerateCodeDraftsUseCase`
  - `ApplyCodeDraftUseCase`
  - `OpenCodeDraftDiffUseCase`
  - `NavigateSourceUseCase`
  - `LoadDebugGraphUseCase`

新增 application model：

- `ApplicationSnapshot`
- `GraphWorkspaceSnapshot`
- `PlanningInput`
- `AuditInput`
- `GenerationInput`
- `UseCaseResult`
- `UseCaseEvent`

`ApplicationSnapshot` 不是 UI snapshot。它只包含业务编排需要的数据：

- canonical graph states
- selected subject metadata
- 当前 draft/workbench 领域状态
- 取消请求或 replay 需要的当前 async request metadata
- generation settings snapshot
- source context/evidence context

规则：

- use case 返回 `UseCaseResult` 或领域 result object。
- use case 可以产生 `UseCaseEvent`，例如 `GraphChanged`、`AuditCompleted`、`DraftChangeRejected`、`FeedbackRequested`、`NativeDiffRequested`。
- use case 不得调用 `GraphEditorStateService`、`ProjectEditorSession.mutate`、`GraphEditorStateMutationContext` 或 browser sync。
- 后台执行和生命周期跟踪属于 application-level request coordinator，但 UI projection 不属于它。

### `ui`

职责：状态投影、bridge payload、渲染、browser lifecycle。

保留或新增：

- `GraphEditorCommandRouter`
- `GraphEditorStateService`
- `GraphEditorStateReducer`
- `GraphEditorStatePresenter`
- `GraphEditorSyncNotifier`
- bridge payload parsing
- browser transport

`GraphEditorCommandRouter` 职责：

- 解析 `GraphEditorMessage`
- 调用 `GraphEditorApplicationService`
- 将 `UseCaseResult` 交给 presenter/reducer
- 通过现有 UI infrastructure 请求 browser sync

`GraphEditorStatePresenter` 职责：

- 将 `UseCaseResult` 和 `UseCaseEvent` 翻译为 UI mutation
- 分配 `OperationFeedbackLevel`
- 更新 async request state
- 更新 graph/workbench state
- 更新 runtime artifact summaries
- 决定是否需要 browser sync

规则：

- UI state mutation 集中在这里。
- UI 可以依赖 application result type。
- application 不得依赖 UI state mutation type。

### `llm`

职责：LLM capability、prompt、gateway、parser、structured schema、tool、agent runtime。

重构要求：

- 移除所有 `llm -> services` 依赖。
- 用 `application.generation.PlanningInput` 或只包含纯字段的 `llm.GenerationContext` 替代 `services.PlanningPayload`。
- 将 debug environment 依赖迁移到 `foundation`。
- 拆分 `GenerationDiagnostics`：
  - LLM 自有 summary 移到 `llm.diagnostics`。
  - application/workflow summary 移到 `application.diagnostics`。
  - UI feedback 文案留在 `ui`。
- LLM tools 不再使用 service graph helper，改为使用 domain snapshot accessor 或 application 提供的 tool context。

规则：

- LLM tools 通过 `ToolExecutionContext` port 获取所有 project/snapshot 访问能力。
- LLM 代码不得 import `GraphEditorStateSnapshot`。
- LLM 代码不得 import `services` 或 `ui` 下的任何内容。

### `semantic`

职责：subject analysis 和 provider dispatch。

重构 code-provider 注册：

```kotlin
interface CodeSubjectSemanticProvider : SemanticProvider {
    val supportedKinds: Set<CodeSubjectKind>
}
```

Registry 行为：

- 对 `CodeSubjectHandle`，按精确 `CodeSubjectKind` 选择 provider。
- 启动或测试阶段拒绝重复 provider kind 注册。
- 缺少 provider 注册时给出明确错误。

Provider 行为：

- `JavaCodeSemanticProvider.supportedKinds = setOf(JAVA_METHOD)`。
- `KotlinCodeSemanticProvider.supportedKinds = setOf(KOTLIN_FUNCTION, KOTLIN_PROPERTY_ACCESSOR, KOTLIN_PRIMARY_CONSTRUCTOR, KOTLIN_SECONDARY_CONSTRUCTOR)`。
- provider 不得使用 `kind != JAVA_METHOD` 作为 support 规则。
- 删除 `CodeSemanticProvider`，或将它改成不包含 hardcoded `when` 分支的 registry-backed adapter。

## Use Case Result 模型

use case 返回明确 result。result 不直接表达 UI mutation。

示例：

```kotlin
sealed interface ConfirmDraftChangeResult {
    data object MissingCandidate : ConfirmDraftChangeResult
    data class Rejected(val reason: String) : ConfirmDraftChangeResult
    data class Confirmed(
        val draftState: DraftWorkbenchState,
        val rebuiltGraph: GraphDocument,
        val updatedAuditResult: GraphPatchResult,
        val confirmedEntry: DraftWorkbenchEntry?,
        val observedNodeIds: List<String>,
    ) : ConfirmDraftChangeResult
}
```

```kotlin
sealed interface AuditResultEvent {
    data class Completed(
        val result: GraphPatchResult,
        val requestState: ApplicationRequestState,
        val draftValidation: DraftValidationState,
        val codeEligibility: CodeEligibilityDecision,
        val runtimeArtifacts: List<RuntimeArtifactSummary>,
    ) : AuditResultEvent

    data class Failed(
        val message: String,
        val requestState: ApplicationRequestState,
        val retryableRequest: ReplayableQaRequest?,
        val runtimeArtifacts: List<RuntimeArtifactSummary>,
    ) : AuditResultEvent
}
```

presenter 将这些 result 映射为：

- `asyncRequests.markAuditResult`
- `asyncRequests.markAuditRequestFailed`
- `workbench.markOperationFeedback`
- `workbench.markDraftValidationState`
- `workbench.markCodeEligibilityDecision`
- `markGraphChanged`
- browser sync request

## 完整链路

### QA 请求

目标链路：

```text
GraphEditorMessage.RequestAudit
  -> GraphEditorCommandRouter
  -> GraphEditorApplicationService.requestAudit
  -> RequestAuditUseCase
  -> QaCapability / GraphAuditPatchService
  -> AuditResultNormalizer / RiskResolutionService
  -> AuditUseCaseResult
  -> GraphEditorStatePresenter.present(result)
  -> GraphEditorStateService mutation + sync notifier
```

关键变化：

- request lifecycle 归 application 所有。
- state projection 归 UI 所有。
- LLM 接收纯 `AuditInput`。
- workflow 不再调用 `session.mutate`。

### Confirm draft change

目标链路：

```text
GraphEditorMessage.ConfirmAuditCandidateChange
  -> GraphEditorCommandRouter
  -> ConfirmDraftChangeUseCase
  -> DraftWorkbenchService + GraphPatchApplyService
  -> ConfirmDraftChangeResult
  -> ConfirmedDraftArtifactWriter
  -> GraphEditorStatePresenter
```

关键变化：

- 纯 confirmation logic 成为所有 workflow 的标准模式。
- artifact writing 是命名明确的副作用组件，不隐藏在 sync workflow 内。
- UI feedback 由 presenter 分配。

### Generation plan 和 code drafts

目标链路：

```text
GraphEditorMessage.RequestGenerationPlan
  -> GraphEditorApplicationService
  -> BuildPlanningInputUseCase
  -> PlanCapability / GraphGenerationService
  -> GenerationPlanResult
  -> GraphEditorStatePresenter
```

关键变化：

- `PlanningPayload` 替换为纯 `PlanningInput`。
- `PlanningInput` 不持有 `GraphEditorStateSnapshot`。
- generation workflow 返回 `GenerationPlanResult`、`GeneratedCodeDraftsResult` 或 `CodeDraftApplyResult`。
- native diff opening 表达为 application event，由 UI/platform adapter 消费。

### Source navigation

目标链路：

```text
GraphEditorMessage.RequestSourceNavigation
  -> NavigateSourceUseCase
  -> SourceNavigationService
  -> NavigationResult
  -> GraphEditorStatePresenter
```

关键变化：

- navigation success/failure 是 result。
- source navigation UI feedback 不由 use case 直接发出。

## 破坏性迁移策略

### 阶段 1：先加入架构门禁

新增定义目标边界的 architecture tests。这些测试可以先失败或标记为待迁移阶段门禁：

- `llmDoesNotDependOnServicesOrUi`
- `applicationDoesNotDependOnUiStateMutation`
- `workflowsDoNotCallSessionMutate`
- `graphEditorRouterDoesNotUseProjectServiceFacade`
- `linkGraphProjectServiceDoesNotExist`
- `planningInputDoesNotContainGraphEditorStateSnapshot`
- `codeSemanticProvidersDeclareSupportedKinds`
- `kotlinProviderDoesNotUseNegativeJavaMatch`

这些测试在当前违规位置应当失败。每个迁移阶段负责让对应子集转绿。

### 阶段 2：抽取 foundation 和纯输入模型

迁移：

- `LinkGraphDebugEnvironment` 到 `foundation`。
- diagnostics 到各自归属层的 diagnostic object。
- `PlanningPayload` 到不包含 UI snapshot 的纯 application/LLM input model。
- LLM tools 使用的 graph snapshot helper 改为 domain/application ports。

验收：

- `llm` 下没有生产文件 import `services` 或 `ui`。
- LLM tests 使用纯输入后通过。
- plan/QA/codegen capability 不再需要 `GraphEditorStateSnapshot`。

### 阶段 3：引入 application use cases 和 presenters

按风险从高到低创建 application use cases 和 result types：

1. confirmed draft change
2. draft patch apply/preview/undo
3. generation plan/code drafts
4. QA/review/beautification
5. subject graph/source navigation/workspace import/export/diff

每个流程都必须：

- 将业务决策逻辑迁入 use case。
- 让 use case 返回 result/event。
- 将 `session.mutate` block 迁入 presenter/reducer。
- 更新测试，分别断言 use-case result 和 UI projection。

验收：

- use-case tests 不实例化 `GraphEditorStateMutationContext`。
- UI presenter tests 验证 state mutation mapping。
- application workflow 不调用 `session.mutate`。

### 阶段 4：删除 legacy command/facade 层

删除 `LinkGraphProjectService` 并迁移所有调用方：

- actions 调用 `GraphEditorApplicationService` 或领域明确的 application services。
- debug automation 调用 application use cases。
- bridge 调用 `GraphEditorCommandRouter`。
- tests 使用 application test fixtures，不再使用 legacy service entrypoints。

检查 `*Commands` services：

- 只有在它们封装真实领域边界或 IntelliJ service registration 边界时才保留。
- 否则删除，并通过 `GraphEditorApplicationService` 路由。

验收：

- 生产代码没有 `LinkGraphProjectService` 引用。
- 测试不依赖 legacy project-service entrypoint。
- `GraphEditorCommandRouter` 依赖 application APIs，而不是 command forwarding stacks。

### 阶段 5：重构 semantic provider 注册

实现 `CodeSubjectSemanticProvider.supportedKinds`。

更新 registry：

- 对 code subject handle 做精确 kind matching。
- 检测 duplicate kind。
- 检测 missing kind。

更新 providers：

- Java provider 声明 Java kinds。
- Kotlin provider 声明 Kotlin kinds。
- 禁止 negative support condition。
- 从 `CodeSemanticProvider` 删除 hardcoded `when`。

验收：

- 测试中新增一个 fake `CodeSubjectKind` 时，在 provider 注册前必须失败。
- Kotlin provider 不认领 unknown/non-Java kinds。
- 现有 Java/Kotlin semantic tests 通过。

### 阶段 6：移除旧 workflow/session mutation 路径

所有流程迁移后：

- 删除或缩小 `ProjectEditorSession`，让它只作为 UI transaction support。
- 删除已经变成空 facade 的 workflow classes。
- reducers/presenters 成为唯一表达 UI state mutation 的位置。

验收：

- architecture test 确认 UI presenter/reducer/session internals 之外没有 `session.mutate` 调用。
- workflow/application tests 使用纯 result assertion。
- 全量测试通过。

## 测试策略

### 架构测试

新增扫描生产 Kotlin 源码的测试：

- forbidden imports by package
- allowed directories 之外的 forbidden symbols
- deleted class existence checks
- semantic provider registration checks

这些测试是必要的，因为目标架构不能只靠约定维持。

### 单元测试

每个 use case 覆盖：

- success result
- rejected/missing input result
- failure result
- side-effect event shape
- 适用时覆盖 cancellation 或 stale request behavior

每个 presenter 覆盖：

- success 到 graph/workbench/async state mutation 的映射
- rejection 到 warning/error feedback 的映射
- 现有 request state rules 的保持
- 仅在需要时请求 browser sync

### 集成测试

保留现有 toolwindow/bridge tests，但更新断言：

- bridge dispatches into application API
- application result is projected into UI state
- frontend payload shape remains stable

### 五个原问题的回归测试

- `GraphAuditPatchService` 和所有 `llm` source 没有 `services` import。
- production workflow 没有直接 `session.mutate`。
- confirmed-draft split pattern 表达为 use case + presenter + artifact writer，并且其他流程也采用同一模式。
- 生产代码不存在 `LinkGraphProjectService`。
- semantic providers 声明精确 supported kind sets。

## 落地计划

这个重构不应作为一个巨大且无法 review 的补丁一次落地。实现应按 staged commits 推进：

1. Boundary tests 和 package scaffolding。
2. Foundation extraction 和 LLM dependency cleanup。
3. Pure application snapshot/input models。
4. Confirmed draft flow migration。
5. Draft patch flow migration。
6. Generation flow migration。
7. QA/review/beautification flow migration。
8. Subject/workspace/navigation flow migration。
9. Legacy facade deletion and caller migration。
10. Semantic provider registry refactor。
11. Dead code removal and final architecture gate。

每个 commit 应保持可编译，或作为一个短 stacked branch 的一部分，在下一个 checkpoint 前允许短暂失败。优先选择较小的 compile-green checkpoint，便于 review。

## 风险和缓解

### 风险：result model 变得过宽

缓解：

- 每个 use case 保留独立 result type。
- 只共享 `ApplicationRequestState` 这类小 primitive。
- 避免创建一个通用 mega-result。

### 风险：presenter 变成新的 god object

缓解：

- 按领域拆分 presenter：
  - `AuditStatePresenter`
  - `DraftPatchStatePresenter`
  - `GenerationStatePresenter`
  - `WorkspaceStatePresenter`
  - `SubjectGraphStatePresenter`
- 只保留一个很小的 coordinator 负责 bridge dispatch。

### 风险：request lifecycle 仍然和 UI state 耦合

缓解：

- application 拥有 request identity、cancellation、stale response check 和 runtime metadata。
- UI 只拥有该生命周期的视觉状态投影。

### 风险：LLM tools 仍需要 project/snapshot 细节

缓解：

- 在 `ToolExecutionContext` 暴露窄 port。
- 传入纯 graph/draft/source accessor。
- 仅在 tool 真实读取项目文件时保留 IntelliJ `Project`，并将其隔离到 facade 后。

### 风险：删除 `LinkGraphProjectService` 会破坏大量测试

缓解：

- 更新测试，改用明确的 application test fixtures。
- 创建 test builders，而不是生产兼容 service。
- 让编译错误枚举所有遗漏迁移点。

## 完成定义

只有以下条件全部满足时，本次重构才算完成：

- 没有 `llm` source import `services` 或 `ui`。
- 没有 application workflow/use case 调用 `session.mutate`、`session.mutateBatch` 或 `GraphEditorStateMutationContext`。
- `LinkGraphProjectService` 已从生产代码删除。
- `GraphEditorCommandRouter` 只依赖 application APIs 和 UI presenter/reducer。
- `PlanningInput` 或等价 generation input 不包含 `GraphEditorStateSnapshot`。
- UI state mutation 集中在 presenters/reducers。
- semantic code providers 声明精确 supported `CodeSubjectKind` sets。
- Kotlin provider 不使用 `kind != JAVA_METHOD`。
- architecture tests 强制执行以上所有规则。
- 用户可见的 QA、draft、generation、navigation、import/export、semantic tests 在入口迁移后全部通过。
