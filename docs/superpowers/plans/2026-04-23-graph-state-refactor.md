# Graph State Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the graph editor's multi-truth snapshot model with a single canonical workspace graph, scene-aware UI state, projection-index-backed editing, and a short-transaction state store.

**Architecture:** The refactor proceeds from invariants outward. First, rewrite tests so they lock the new truth model instead of the legacy behavior. Then replace the state core with `semanticFactGraph / workspaceBaseGraph / workspaceGraph`, rebuild projections and command mapping around `projectionIndex`, migrate workflows and transport to command-based updates, and finally rewrite the frontend state shell to consume authoritative scene-aware snapshots without local structural mirrors.

**Tech Stack:** Kotlin, IntelliJ Platform services, React, TypeScript, Vitest, JUnit, Gradle

---

### Task 1: Lock the new invariants with failing tests

**Files:**
- Modify: `src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateServiceTest.kt`
- Modify: `src/test/kotlin/com/charmnight/linkgraph/services/GraphWorkspaceWorkflowTest.kt`
- Modify: `src/test/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectServiceSemanticAnalysisTest.kt`
- Modify: `src/test/kotlin/com/charmnight/linkgraph/services/GraphEditorStateSyncSessionTest.kt`
- Modify: `web/src/test/app/workingGraphDocument.test.ts`
- Modify: `web/src/test/app/bridgeContract.test.ts`
- Modify: `web/src/test/app/threeViewArchitecture.test.ts`
- Create: `src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateStoreTest.kt`
- Create: `web/src/test/app/sceneStateArchitecture.test.ts`

- [ ] **Step 1: Write failing backend tests for the new truth model**

Add or rewrite tests so they require:
- no `markViewGraphChanged`-style partial view persistence
- no acceptance of stale whole-snapshot replacement
- resource subject `FLOWCHART` requests to resolve to `RESOURCE_RELATION_VIEW`
- scene-aware selection and layout state to be isolated from diff state

- [ ] **Step 2: Write failing frontend tests for the new transport contract**

Add or rewrite tests so they require:
- no `graphChanged` full-document writeback contract
- scene-specific selection/layout behavior
- no `workingGraphDocument` fallback helper
- snapshot payloads to expose workspace/projection/scene state instead of legacy top-level graph aliases

- [ ] **Step 3: Run the focused tests to verify red**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.ui.GraphEditorStateServiceTest' --tests 'com.charmnight.linkgraph.ui.GraphEditorStateStoreTest' --tests 'com.charmnight.linkgraph.services.GraphWorkspaceWorkflowTest' --tests 'com.charmnight.linkgraph.services.LinkGraphProjectServiceSemanticAnalysisTest' --tests 'com.charmnight.linkgraph.services.GraphEditorStateSyncSessionTest'`

Run:
`cd web && npm test -- --runInBand src/test/app/workingGraphDocument.test.ts src/test/app/bridgeContract.test.ts src/test/app/threeViewArchitecture.test.ts src/test/app/sceneStateArchitecture.test.ts`

Expected:
- backend tests fail on legacy snapshot/session/view behavior
- frontend tests fail on legacy graphChanged contract and legacy bootstrap fields

### Task 2: Replace the backend state core with canonical workspace state and a short-transaction store

**Files:**
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateModels.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorSnapshot.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateMutationContext.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorSnapshotMutations.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorInteractionMutations.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorGraphStateSupport.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/ProjectEditorSession.kt`
- Delete: `src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorStateSyncSession.kt`
- Create: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateStore.kt`
- Create: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorSceneState.kt`

- [ ] **Step 1: Re-run the new state-core tests to confirm the current failures**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.ui.GraphEditorStateServiceTest' --tests 'com.charmnight.linkgraph.ui.GraphEditorStateStoreTest' --tests 'com.charmnight.linkgraph.services.GraphEditorStateSyncSessionTest'`

Expected:
- failures on stale overwrite behavior
- failures on legacy top-level graph fields
- failures on missing scene-aware state

- [ ] **Step 2: Introduce the new canonical state model**

Implement:
- `semanticFactGraph`
- `workspaceBaseGraph`
- `workspaceGraph`
- `designBaselineGraph`
- `currentSceneId`
- `sceneStates`
- store / workspace / layout revisions

Delete legacy truth fields from the state model rather than aliasing them.

- [ ] **Step 3: Replace draft snapshot batching with a short-transaction store**

Implement:
- `GraphEditorStateStore`
- CAS-like commit semantics
- state mutation entry points that operate on current state instead of stale draft copies
- scene-aware layout mutation paths

Delete:
- `GraphEditorStateSyncSession`
- whole-snapshot replace paths

- [ ] **Step 4: Rebuild state mutation helpers against the new model**

Implement minimal mutation support for:
- graph load / analysis load
- workspace graph replacement
- scene switch
- layout change by scene
- diff scene enter/exit

- [ ] **Step 5: Re-run the focused backend tests to verify green**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.ui.GraphEditorStateServiceTest' --tests 'com.charmnight.linkgraph.ui.GraphEditorStateStoreTest' --tests 'com.charmnight.linkgraph.services.GraphEditorStateSyncSessionTest'`

Expected:
- PASS
- no references remain to `replaceSnapshot(nextState)` or legacy batch session flow

### Task 3: Rebuild projection documents around `workspaceGraph` and `projectionIndex`

**Files:**
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorViewSupport.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorGraphMutations.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/view/FactGraphViewDocument.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/view/FlowchartViewDocument.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/view/ResourceRelationViewDocument.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/view/ReadableFlowchartProjection.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/view/FactGraphProjector.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/view/FlowchartProjector.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/view/ResourceRelationProjector.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/InteractiveGraphProjector.kt`
- Create: `src/main/kotlin/com/charmnight/linkgraph/ui/view/GraphProjectionIndex.kt`
- Create: `src/test/kotlin/com/charmnight/linkgraph/ui/view/GraphProjectionIndexTest.kt`

- [ ] **Step 1: Write failing tests for projection mapping**

Add tests that require:
- fact/resource/flowchart views to derive from the same `workspaceGraph`
- flowchart merged nodes to expose alias metadata through `projectionIndex`
- synthetic or overflow elements to be marked read-only
- edge insert/delete operations to reject ambiguous path aliases

- [ ] **Step 2: Run the focused projector tests to verify red**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.ui.view.FlowchartProjectorTest' --tests 'com.charmnight.linkgraph.ui.view.GraphProjectionIndexTest'`

Expected:
- FAIL because current projectors do not expose full command-grade mapping metadata

- [ ] **Step 3: Add `projectionIndex` to all workspace view documents**

Implement:
- node mapping kind
- edge mapping kind
- canonical node / edge references
- editable command kinds
- explicit read-only classification for synthetic and overflow elements

- [ ] **Step 4: Rework projector output to consume only `workspaceGraph`**

Implement:
- pure projection bundle rebuild from canonical workspace graph
- no partial “keep other view as-is” behavior
- deterministic anchor mapping via projection index

- [ ] **Step 5: Re-run the focused projector tests to verify green**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.ui.view.FlowchartProjectorTest' --tests 'com.charmnight.linkgraph.ui.view.GraphProjectionIndexTest'`

Expected:
- PASS

### Task 4: Rewire backend workflows to the new workspace/base/fact semantics

**Files:**
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/GraphSnapshotDocuments.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/GraphWorkspaceWorkflow.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/SubjectGraphWorkflow.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/PlanningContextFactory.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/ConfirmedDraftChangeWorkflow.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/ConfirmedDraftChangeSyncWorkflow.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/AsyncRequestLifecycleSupport.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt`
- Modify: `src/test/kotlin/com/charmnight/linkgraph/services/GraphWorkspaceWorkflowTest.kt`
- Modify: `src/test/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectServiceSemanticAnalysisTest.kt`
- Modify: `src/test/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectServicePlanningTest.kt`

- [ ] **Step 1: Re-run the focused workflow tests to confirm failure on legacy semantics**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.services.GraphWorkspaceWorkflowTest' --tests 'com.charmnight.linkgraph.services.LinkGraphProjectServiceSemanticAnalysisTest' --tests 'com.charmnight.linkgraph.services.LinkGraphProjectServicePlanningTest'`

Expected:
- FAIL on resource display fallback
- FAIL on old working/reference graph consumption
- FAIL on stale batch/session assumptions

- [ ] **Step 2: Move all structure-edit workflows to `workspaceGraph`**

Implement:
- frontend edits apply only to canonical workspace graph
- draft confirm/unconfirm rebuilds against `workspaceBaseGraph`
- planning/beautification/audit choose between `workspaceGraph`, `semanticFactGraph`, and `designBaselineGraph` explicitly

- [ ] **Step 3: Enforce resource-subject display policy**

Implement:
- resource subjects default to `WORKSPACE_RESOURCE_RELATION`
- `FLOWCHART` request for resource subjects degrades to resource relation instead of fact graph

- [ ] **Step 4: Enforce short-transaction async result commits**

Implement:
- “compute outside store, commit against expected revision”
- stale async completions are dropped or retried instead of overwriting the latest state

- [ ] **Step 5: Re-run the focused workflow tests to verify green**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.services.GraphWorkspaceWorkflowTest' --tests 'com.charmnight.linkgraph.services.LinkGraphProjectServiceSemanticAnalysisTest' --tests 'com.charmnight.linkgraph.services.LinkGraphProjectServicePlanningTest'`

Expected:
- PASS

### Task 5: Replace the bridge protocol with command-based graph editing and new snapshot payloads

**Files:**
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorMessage.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserBridgeRegistrar.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorCommandRouter.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRenderer.kt`
- Modify: `src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorTransportSliceRenderer.kt`
- Modify: `src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRendererTest.kt`
- Modify: `src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorBridgeTest.kt`
- Modify: `src/test/kotlin/com/charmnight/linkgraph/ui/GraphEditorTransportSliceRendererTest.kt`
- Modify: `web/src/test/app/bridgeContract.test.ts`
- Modify: `web/src/test/app/api.test.ts`

- [ ] **Step 1: Re-run the bridge/transport tests to confirm legacy contract failures**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.ui.GraphEditorBridgeTest' --tests 'com.charmnight.linkgraph.ui.GraphEditorPageRendererTest' --tests 'com.charmnight.linkgraph.ui.GraphEditorTransportSliceRendererTest'`

Run:
`cd web && npm test -- --runInBand src/test/app/bridgeContract.test.ts src/test/app/api.test.ts`

Expected:
- FAIL because current bridge still uses `GraphChanged(GraphDocument)` and legacy bootstrap payload fields

- [ ] **Step 2: Replace structural edit messages with command payloads**

Implement command messages for:
- add/update/delete node
- delete subtree
- connect/delete edge
- insert node into edge
- move nodes layout

Delete the full-graph writeback message entirely.

- [ ] **Step 3: Rewrite snapshot payloads to the new model**

Implement payload sections for:
- workspace
- projections
- sceneStates
- currentSceneId
- workbench/request slices

Remove:
- `visibleGraph`
- `workingGraph`
- `referenceWorkingGraph`
- `referenceFactGraph`

- [ ] **Step 4: Update the frontend bridge client contract**

Implement:
- command publishing helpers
- scene/revision/projection-aware request payloads
- no local structural snapshot upload path

- [ ] **Step 5: Re-run the bridge/transport tests to verify green**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.ui.GraphEditorBridgeTest' --tests 'com.charmnight.linkgraph.ui.GraphEditorPageRendererTest' --tests 'com.charmnight.linkgraph.ui.GraphEditorTransportSliceRendererTest'`

Run:
`cd web && npm test -- --runInBand src/test/app/bridgeContract.test.ts src/test/app/api.test.ts`

Expected:
- PASS

### Task 6: Rebuild the frontend around authoritative scene-aware state and command-driven editing

**Files:**
- Modify: `web/src/app/types.ts`
- Modify: `web/src/app/sampleState.ts`
- Modify: `web/src/app/App.tsx`
- Modify: `web/src/app/api.ts`
- Modify: `web/src/app/graphState.ts`
- Modify: `web/src/app/layoutEditability.ts`
- Modify: `web/src/app/controllers/useWorkbenchState.ts`
- Modify: `web/src/app/controllers/useBootstrapProjectionState.ts`
- Modify: `web/src/app/controllers/useGraphEditController.ts`
- Modify: `web/src/app/controllers/useGraphCanvasController.ts`
- Modify: `web/src/app/controllers/useWorkbenchDerivedState.ts`
- Delete: `web/src/app/workingGraphDocument.ts`
- Modify: `web/src/test/app/App.test.tsx`
- Modify: `web/src/test/app/App.layoutBridge.test.tsx`
- Modify: `web/src/test/app/App.bootstrapRevision.test.tsx`
- Modify: `web/src/test/app/threeViewArchitecture.test.ts`
- Modify: `web/src/test/app/components/graph/actions/actionSchema.test.ts`
- Modify: `web/src/test/app/graphState.test.ts`
- Modify: `web/src/test/app/sceneStateArchitecture.test.ts`

- [ ] **Step 1: Re-run the focused frontend tests to confirm the pre-refactor failures**

Run:
`cd web && npm test -- --runInBand src/test/app/App.test.tsx src/test/app/App.layoutBridge.test.tsx src/test/app/App.bootstrapRevision.test.tsx src/test/app/threeViewArchitecture.test.ts src/test/app/components/graph/actions/actionSchema.test.ts src/test/app/graphState.test.ts src/test/app/sceneStateArchitecture.test.ts`

Expected:
- FAIL on missing scene-aware state
- FAIL on deleted legacy bootstrap aliases
- FAIL on local structural edit assumptions

- [ ] **Step 2: Replace the frontend state shape with workspace/projection/scene slices**

Implement:
- workspace slice
- projections slice
- scene state slice
- local scene-only UI state

Remove:
- legacy top-level `workingGraph` inference
- `workingGraphDocument` fallback helper

- [ ] **Step 3: Convert structure editing to command publication**

Implement:
- no local full-graph structural mirror
- command publishing for graph structure changes
- layout-only optimistic updates per scene
- backend-issued node ids as canonical ids

- [ ] **Step 4: Make selection/anchor/layout fully scene-aware**

Implement:
- workspace fact / flowchart / resource / diff scene separation
- scene-specific collapse, detail, and diff targeting state
- no cross-scene `ui.x / ui.y` bleed

- [ ] **Step 5: Re-run the focused frontend tests to verify green**

Run:
`cd web && npm test -- --runInBand src/test/app/App.test.tsx src/test/app/App.layoutBridge.test.tsx src/test/app/App.bootstrapRevision.test.tsx src/test/app/threeViewArchitecture.test.ts src/test/app/components/graph/actions/actionSchema.test.ts src/test/app/graphState.test.ts src/test/app/sceneStateArchitecture.test.ts`

Expected:
- PASS

### Task 7: Delete dead compatibility paths and run full regression

**Files:**
- Delete: `web/src/app/workingGraphDocument.ts`
- Delete or remove references from: `src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorStateSyncSession.kt`
- Delete or remove references from: legacy `markViewGraphChanged` call sites
- Modify: all affected files to remove dead imports, dead tests, and dead helper paths

- [ ] **Step 1: Audit the tree for forbidden legacy symbols**

Run:
`rg -n "markViewGraphChanged|GraphChanged\\(|replaceSnapshot\\(|newDraftMutationContext|visibleGraph\\b|workingGraph\\b|referenceWorkingGraph|referenceFactGraph|diffMode\\b|resolveWorkingGraphDocument|publishGraphChange" src/main/kotlin web/src/app src/test/kotlin web/src/test`

Expected:
- no remaining architectural usage of removed symbols
- only intentional mentions inside migration-proof tests/docs if any

- [ ] **Step 2: Run impacted backend regression suites**

Run:
`./gradlew test --tests 'com.charmnight.linkgraph.ui.GraphEditorStateServiceTest' --tests 'com.charmnight.linkgraph.ui.GraphEditorPageRendererTest' --tests 'com.charmnight.linkgraph.ui.GraphEditorTransportSliceRendererTest' --tests 'com.charmnight.linkgraph.services.GraphWorkspaceWorkflowTest' --tests 'com.charmnight.linkgraph.services.LinkGraphProjectServiceSemanticAnalysisTest' --tests 'com.charmnight.linkgraph.services.LinkGraphProjectServicePlanningTest' --tests 'com.charmnight.linkgraph.services.ConfirmedDraftChangeSyncWorkflowTest'`

Expected:
- PASS

- [ ] **Step 3: Run impacted frontend regression suites**

Run:
`cd web && npm test -- --runInBand src/test/app/App.test.tsx src/test/app/App.layoutBridge.test.tsx src/test/app/App.bootstrapRevision.test.tsx src/test/app/bridgeContract.test.ts src/test/app/api.test.ts src/test/app/threeViewArchitecture.test.ts src/test/app/graphState.test.ts src/test/app/components/graph/actions/actionSchema.test.ts src/test/app/sceneStateArchitecture.test.ts`

Expected:
- PASS

- [ ] **Step 4: Run broad verification**

Run:
`./gradlew test`

Run:
`cd web && npm test -- --runInBand`

Expected:
- PASS or a clearly identified pre-existing failure backed by fresh output

- [ ] **Step 5: Summarize the finished tree and residual risks**

Document:
- what legacy paths were deleted
- what new invariants are now enforced
- any residual areas not fully covered by automated tests
