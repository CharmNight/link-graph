# Link Graph Architecture Boundary Refactor Design

## Goal

Fix the current architecture boundary problems with a destructive, complete-link refactor rather than compatibility wrappers or one-off patches.

The refactor must address these five issues together:

1. `llm` depends on `services`.
2. workflows directly mutate UI state.
3. confirmed-draft workflow split is inconsistent with the rest of the system.
4. command/router/project-service layers contain legacy forwarding.
5. semantic code providers are coupled to `CodeSubjectKind` branching and negative matching.

## Non-Goals

- No compatibility facade for `LinkGraphProjectService`.
- No bridge layer that keeps old APIs alive while internally calling new APIs.
- No temporary duplicate command path for production callers.
- No broad UI redesign.
- No change to end-user behavior except where required by cleaner state/error handling.

## Coverage Matrix

| Original issue | Required structural fix | Validation gate |
| --- | --- | --- |
| 4.1 `llm -> services` reverse dependency | Move debug/env and layer-neutral helpers to `foundation`; move planning payloads to pure application/LLM input models; split diagnostics by owning layer; remove service graph helpers from LLM tools. | Source scan fails on any `src/main/kotlin/.../llm/**` import of `services` or `ui`; LLM input models must not reference `GraphEditorStateSnapshot`. |
| 4.2 workflows mutate UI state | Convert workflow behavior into application use cases returning explicit result/event objects; move UI mutation to presenters/reducers. | Source scan fails on `session.mutate`, `session.mutateBatch`, or `GraphEditorStateMutationContext` outside allowed UI presenter/reducer/session internals. |
| 4.3 confirmed-draft split is inconsistent | Generalize the confirmed-draft pattern to all flows as use case + presenter + named side-effect writer; remove `SyncWorkflow` naming and role mixing. | Tests must exist for pure use-case result and separate presenter mapping for confirmed draft, draft patch, generation, QA, subject/workspace, and navigation flows. |
| 4.4 router/facade forwarding | Delete `LinkGraphProjectService`; remove thin command stacks unless they provide real IntelliJ service registration or domain boundary value; make router call application APIs directly. | Production source must not contain `LinkGraphProjectService`; router must not depend on legacy command forwarding stacks. |
| 4.5 provider kind coupling | Replace negative `supports()` checks and hardcoded code-provider `when` branches with exact `supportedKinds` registration. | Tests fail on duplicate/missing `CodeSubjectKind` registration and source scan fails on `kind != CodeSubjectKind.JAVA_METHOD`. |

## Current Root Cause

The current `services` package acts as a catch-all boundary. It owns application workflows, UI state mutation helpers, debug utilities, planning payloads, project service entrypoints, and shared diagnostics. This causes both upward and downward dependencies:

- `services -> llm` for generation, QA, runtime, and parsing.
- `llm -> services` for debug environment, diagnostics, planning payloads, and graph snapshot helpers.
- `services -> ui` through `ProjectEditorSession`, `GraphEditorStateMutationContext`, and direct workbench mutation calls.
- `ui -> services` through bridge/router/command services.

Because the package boundary is not explicit, fixing a single import would only move the violation. The design must create enforceable dependency direction and make invalid dependencies fail tests.

## Target Dependency Direction

Production code must follow this direction:

```text
actions/toolwindow/ui
  -> application
  -> domain modules + llm + codegen + semantic + sync + navigation + settings
  -> model/foundation
```

Allowed dependency rules:

- `llm` may depend on `model`, `workbench` domain models, settings runtime state, and `foundation`.
- `llm` must not depend on `services`, `ui`, `toolwindow`, or `actions`.
- `application` may orchestrate `llm`, `semantic`, `sync`, `codegen`, `navigation`, `settings`, and domain services.
- `application` must not mutate `GraphEditorStateService` directly.
- `ui` owns rendering, bridge payloads, state reducers, and browser sync.
- `actions` and `toolwindow` enter the system through application command/use-case APIs.
- `services` as a package is either removed or reduced to IntelliJ service registration adapters only. It must not contain business workflows.

## Package Shape

### `foundation`

Purpose: shared infrastructure that is safe for any layer.

Move or create:

- `LinkGraphDebugEnvironment`
- trace flag helpers
- small diagnostic formatting primitives that do not know about UI state
- generic `OperationFailure`, `UseCaseError`, and result helpers if needed

Rules:

- No dependency on `services`, `ui`, `llm`, `application`, or IntelliJ project services unless the helper is explicitly platform infrastructure.
- Debug helpers must be injectable where runtime tests need deterministic values.

### `application`

Purpose: project-level business orchestration.

Create:

- `GraphEditorApplicationService`
- use cases:
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

Create application models:

- `ApplicationSnapshot`
- `GraphWorkspaceSnapshot`
- `PlanningInput`
- `AuditInput`
- `GenerationInput`
- `UseCaseResult`
- `UseCaseEvent`

`ApplicationSnapshot` is not a UI snapshot. It contains only the data required by business orchestration:

- canonical graph states
- selected subject metadata
- current draft/workbench domain state
- current async request metadata when needed for cancellation or replay
- generation settings snapshot
- source context/evidence context

Rules:

- Use cases return `UseCaseResult` or domain result objects.
- Use cases may emit `UseCaseEvent`s such as `GraphChanged`, `AuditCompleted`, `DraftChangeRejected`, `FeedbackRequested`, and `NativeDiffRequested`.
- Use cases must not call `GraphEditorStateService`, `ProjectEditorSession.mutate`, `GraphEditorStateMutationContext`, or browser sync.
- Background execution and lifecycle tracking live in application-level request coordinators, but UI projection remains outside them.

### `ui`

Purpose: state projection, bridge payloads, rendering, browser lifecycle.

Keep or create:

- `GraphEditorCommandRouter`
- `GraphEditorStateService`
- `GraphEditorStateReducer`
- `GraphEditorStatePresenter`
- `GraphEditorSyncNotifier`
- bridge payload parsing
- browser transport

`GraphEditorCommandRouter` responsibilities:

- parse `GraphEditorMessage`
- call `GraphEditorApplicationService`
- pass `UseCaseResult` to presenter/reducer
- request browser sync through existing UI infrastructure

`GraphEditorStatePresenter` responsibilities:

- translate `UseCaseResult` and `UseCaseEvent` to UI mutations
- assign `OperationFeedbackLevel`
- update async request state
- update graph/workbench state
- update runtime artifact summaries
- decide whether browser sync is required

Rules:

- UI state mutation is centralized here.
- UI may depend on application result types.
- Application must not depend on UI state mutation types.

### `llm`

Purpose: LLM capability, prompt, gateway, parsing, structured schemas, tools, and agent runtime.

Refactor:

- Move all `llm -> services` dependencies out.
- Replace `services.PlanningPayload` with `application.generation.PlanningInput` or an `llm.GenerationContext` input that contains only pure fields.
- Move debug environment dependency to `foundation`.
- Split `GenerationDiagnostics`:
  - LLM-owned summaries move to `llm.diagnostics`.
  - application/workflow summaries move to `application.diagnostics`.
  - UI feedback text stays in `ui`.
- Replace service graph helpers used by LLM tools with domain snapshot accessors or application-supplied tool context.

Rules:

- LLM tools receive all project/snapshot access through `ToolExecutionContext` ports.
- LLM code must not import `GraphEditorStateSnapshot`.
- LLM code must not import anything under `services` or `ui`.

### `semantic`

Purpose: subject analysis and provider dispatch.

Refactor code-provider registration:

```kotlin
interface CodeSubjectSemanticProvider : SemanticProvider {
    val supportedKinds: Set<CodeSubjectKind>
}
```

Registry behavior:

- For `CodeSubjectHandle`, select providers by exact `CodeSubjectKind`.
- Reject duplicate provider registrations for a kind at startup/test time.
- Reject missing provider registration with a clear error.

Provider behavior:

- `JavaCodeSemanticProvider.supportedKinds = setOf(JAVA_METHOD)`.
- `KotlinCodeSemanticProvider.supportedKinds = setOf(KOTLIN_FUNCTION, KOTLIN_PROPERTY_ACCESSOR, KOTLIN_PRIMARY_CONSTRUCTOR, KOTLIN_SECONDARY_CONSTRUCTOR)`.
- No provider may use `kind != JAVA_METHOD` as a support rule.
- `CodeSemanticProvider` is removed or becomes a thin registry-backed adapter without hardcoded `when` branches.

## Use Case Result Model

Use cases return explicit results. They do not directly express UI mutations.

Examples:

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

The presenter maps those results to:

- `asyncRequests.markAuditResult`
- `asyncRequests.markAuditRequestFailed`
- `workbench.markOperationFeedback`
- `workbench.markDraftValidationState`
- `workbench.markCodeEligibilityDecision`
- `markGraphChanged`
- browser sync requests

## Complete Link Flow

### QA request

Target flow:

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

Key changes:

- request lifecycle remains application-owned
- state projection becomes UI-owned
- LLM receives pure `AuditInput`
- no workflow calls `session.mutate`

### Confirm draft change

Target flow:

```text
GraphEditorMessage.ConfirmAuditCandidateChange
  -> GraphEditorCommandRouter
  -> ConfirmDraftChangeUseCase
  -> DraftWorkbenchService + GraphPatchApplyService
  -> ConfirmDraftChangeResult
  -> ConfirmedDraftArtifactWriter
  -> GraphEditorStatePresenter
```

Key changes:

- pure confirmation logic becomes the standard pattern for all workflows
- artifact writing is a named side-effect component, not hidden inside sync workflow
- UI feedback is assigned by presenter

### Generation plan and code drafts

Target flow:

```text
GraphEditorMessage.RequestGenerationPlan
  -> GraphEditorApplicationService
  -> BuildPlanningInputUseCase
  -> PlanCapability / GraphGenerationService
  -> GenerationPlanResult
  -> GraphEditorStatePresenter
```

Key changes:

- `PlanningPayload` is replaced with pure `PlanningInput`
- `PlanningInput` does not hold `GraphEditorStateSnapshot`
- generation workflows return `GenerationPlanResult`, `GeneratedCodeDraftsResult`, or `CodeDraftApplyResult`
- native diff opening is represented as an application event consumed by UI/platform adapter

### Source navigation

Target flow:

```text
GraphEditorMessage.RequestSourceNavigation
  -> NavigateSourceUseCase
  -> SourceNavigationService
  -> NavigationResult
  -> GraphEditorStatePresenter
```

Key changes:

- navigation success/failure is a result
- source navigation UI feedback is not emitted from the use case

## Destructive Migration Strategy

### Phase 1: Add architecture gates first

Add failing or pending architecture tests that define the new boundary:

- `llmDoesNotDependOnServicesOrUi`
- `applicationDoesNotDependOnUiStateMutation`
- `workflowsDoNotCallSessionMutate`
- `graphEditorRouterDoesNotUseProjectServiceFacade`
- `linkGraphProjectServiceDoesNotExist`
- `planningInputDoesNotContainGraphEditorStateSnapshot`
- `codeSemanticProvidersDeclareSupportedKinds`
- `kotlinProviderDoesNotUseNegativeJavaMatch`

These tests should initially fail where the current code violates the target. Each phase turns a subset green.

### Phase 2: Extract foundation and pure inputs

Move:

- `LinkGraphDebugEnvironment` to `foundation`.
- diagnostics into layer-owned diagnostic objects.
- `PlanningPayload` into a pure application/LLM input model with no UI snapshot.
- graph snapshot helper functions used by LLM tools into domain/application ports.

Acceptance:

- no production file under `llm` imports `services` or `ui`
- LLM tests pass with pure inputs
- plan/QA/codegen capabilities no longer need `GraphEditorStateSnapshot`

### Phase 3: Introduce application use cases and presenters

Create application use cases and result types for the highest-risk flows first:

1. confirmed draft change
2. draft patch apply/preview/undo
3. generation plan/code drafts
4. QA/review/beautification
5. subject graph/source navigation/workspace import/export/diff

For each flow:

- move business decision logic into use case
- make use case return result/event
- move `session.mutate` block to presenter/reducer
- update tests to assert use-case results separately from UI projection

Acceptance:

- use-case tests do not instantiate `GraphEditorStateMutationContext`
- UI presenter tests verify state mutation mapping
- no application workflow calls `session.mutate`

### Phase 4: Delete legacy command/facade layers

Delete `LinkGraphProjectService` and update all callers:

- actions call `GraphEditorApplicationService` or domain-specific application services
- debug automation calls application use cases
- bridge calls `GraphEditorCommandRouter`
- tests use application test fixtures instead of legacy service entrypoints

Review `*Commands` services:

- keep only if they encapsulate a real domain boundary or IntelliJ service registration boundary
- otherwise delete and route through `GraphEditorApplicationService`

Acceptance:

- no production code references `LinkGraphProjectService`
- no test depends on legacy project-service entrypoints
- `GraphEditorCommandRouter` depends on application APIs, not command forwarding stacks

### Phase 5: Refactor semantic provider registration

Implement `CodeSubjectSemanticProvider.supportedKinds`.

Update registry:

- exact kind matching for code subject handles
- duplicate kind detection
- missing kind detection

Update providers:

- Java declares Java kinds
- Kotlin declares Kotlin kinds
- no negative support condition
- remove hardcoded `when` from `CodeSemanticProvider`

Acceptance:

- adding a fake new `CodeSubjectKind` in tests fails until a provider is registered
- Kotlin provider does not claim unknown/non-Java kinds
- existing Java/Kotlin semantic tests pass

### Phase 6: Remove old workflow/session mutation path

After all flows are migrated:

- delete or shrink `ProjectEditorSession` to UI-only transaction support
- remove workflow classes that became empty facades
- keep reducers/presenters as the only place where UI state mutation is expressed

Acceptance:

- architecture test confirms zero `session.mutate` calls outside UI presenter/reducer/session internals
- workflow/application tests use pure result assertions
- full test suite passes

## Testing Strategy

### Architecture tests

Add tests that scan production Kotlin sources:

- forbidden imports by package
- forbidden symbols outside allowed directories
- deleted class existence checks
- semantic provider registration checks

These tests are required because the target architecture must not depend on convention.

### Unit tests

For each use case:

- success result
- rejected/missing input result
- failure result
- side-effect event shape
- cancellation or stale request behavior where applicable

For each presenter:

- maps success to expected graph/workbench/async state mutations
- maps rejection to warning/error feedback
- preserves existing request state rules
- requests browser sync only when needed

### Integration tests

Keep existing toolwindow/bridge tests, but update them to assert:

- bridge dispatches into application API
- application result is projected into UI state
- frontend payload shape remains stable

### Regression tests for the five original issues

- `GraphAuditPatchService` and all `llm` sources have no `services` import.
- production workflows have no direct `session.mutate`.
- confirmed-draft split pattern is represented as use case + presenter + artifact writer, and the same pattern exists for other flows.
- no `LinkGraphProjectService` production class exists.
- semantic providers declare exact supported kind sets.

## Rollout Plan

This refactor should not be attempted as one large unreviewable patch. The implementation should land in staged commits:

1. Boundary tests and package scaffolding.
2. Foundation extraction and LLM dependency cleanup.
3. Pure application snapshot/input models.
4. Confirmed draft flow migration.
5. Draft patch flow migration.
6. Generation flow migration.
7. QA/review/beautification flow migration.
8. Subject/workspace/navigation flow migration.
9. Legacy facade deletion and caller migration.
10. Semantic provider registry refactor.
11. Dead code removal and final architecture gate.

Each commit must compile or be part of a short stacked branch where failing tests are expected only until the next checkpoint. Prefer smaller compile-green checkpoints for review.

## Risks And Mitigations

### Risk: Result models become too broad

Mitigation:

- keep result types per use case
- share only small primitives such as `ApplicationRequestState`
- avoid a single generic mega-result

### Risk: Presenter becomes the new god object

Mitigation:

- split presenters by domain:
  - `AuditStatePresenter`
  - `DraftPatchStatePresenter`
  - `GenerationStatePresenter`
  - `WorkspaceStatePresenter`
  - `SubjectGraphStatePresenter`
- keep a small coordinator only for bridge dispatch

### Risk: Request lifecycle remains coupled to UI state

Mitigation:

- application owns request identity, cancellation, stale response checks, and runtime metadata
- UI owns only visual state projection of that lifecycle

### Risk: LLM tools still need project/snapshot details

Mitigation:

- expose narrow tool ports in `ToolExecutionContext`
- pass pure graph/draft/source accessors
- keep IntelliJ `Project` only where a tool genuinely reads project files, and isolate that behind a facade

### Risk: Deleting `LinkGraphProjectService` breaks many tests

Mitigation:

- update tests to use explicit application test fixtures
- create test builders, not production compatibility services
- let compile errors enumerate missed migration sites

## Definition Of Done

The refactor is complete only when all conditions are true:

- No `llm` source imports `services` or `ui`.
- No application workflow/use case calls `session.mutate`, `session.mutateBatch`, or `GraphEditorStateMutationContext`.
- `LinkGraphProjectService` is deleted from production code.
- `GraphEditorCommandRouter` depends on application APIs and UI presenter/reducer only.
- `PlanningInput` or equivalent generation input contains no `GraphEditorStateSnapshot`.
- UI state mutation is centralized in presenters/reducers.
- Semantic code providers declare exact supported `CodeSubjectKind` sets.
- Kotlin provider does not use `kind != JAVA_METHOD`.
- Architecture tests enforce every rule above.
- Existing user-facing QA, draft, generation, navigation, import/export, and semantic tests pass after their entrypoints are migrated.
