# Semantic Analysis Kernel Rewrite Design

## Goal

Replace the current mixed Java/Kotlin/resource extraction pipeline with a unified semantic analysis kernel that:

- supports Java, Kotlin, XML, Markdown, SQL, YAML, and Properties as first-class inputs
- produces one language-agnostic semantic result
- generates multiple presentation modes from the same semantic truth
- connects cleanly to the existing IntelliJ UI, state, and JCEF transport layers
- eliminates the current pattern where fixing one language or entry type regresses another

## Problem Statement

The current implementation mixes several concerns inside the same classes:

- caret-based subject discovery
- language-specific PSI decoding
- Kotlin light-method adaptation
- resource-file parsing
- semantic extraction
- graph node/edge construction
- presentation-oriented truncation behavior

This causes structural instability:

1. The editor entry layer already contains language-specific branches.
2. The extraction layer mixes Java and Kotlin semantic logic inside classes named and shaped for Java.
3. Resource files are handled by ad hoc context mapping instead of a first-class semantic abstraction.
4. Graph construction happens too early, before semantic normalization is complete.
5. Control-flow capture, traversal budgeting, and canvas truncation are not represented as separate policies.

As a result, correctness is fragile. Java/Kotlin/resource behaviors interact through hidden coupling instead of explicit contracts.

## Root Cause

The current pipeline does not have a stable semantic intermediate representation.

The real root cause is not "Kotlin support is incomplete" or "resource parsing uses regex." Those are symptoms. The deeper issue is that the system jumps directly from PSI or editor context to graph-oriented objects, with language-specific branches distributed across multiple layers.

Without a unified semantic result:

- each language/provider invents slightly different meanings for method, flow, boundary, and reference
- UI-facing graph concepts leak back into extraction
- presentation requirements influence extraction behavior
- resource inputs cannot share a common downstream integration path with code inputs

## Design Principles

1. One semantic truth, many presentations.
2. Language parsing and resource parsing are separate provider families, but they must emit the same semantic protocol.
3. PSI is an implementation detail of providers, not the system-wide contract.
4. Graph building is downstream of semantic normalization.
5. Presentation truncation must never define semantic truth.
6. Existing UI/state transport should remain connected through a narrow integration boundary.

## Target Architecture

The rewritten kernel will use this pipeline:

`Editor/Caret -> SubjectLocator -> SubjectHandle -> SemanticProvider -> SemanticAnalysisResult -> AnalysisOutcomeFactory -> AnalysisOutcome -> applyAnalysisOutcome -> GraphEditorStateService -> Frontend`

### Layer Responsibilities

#### 1. SubjectLocator

Purpose:

- identify the editor subject under the caret
- normalize editor-origin concerns into a single handle protocol

Responsibilities:

- commit/read PSI safely
- resolve current caret region
- produce a subject handle instead of directly returning `PsiMethod` or `GraphNode`

Non-responsibilities:

- no semantic extraction
- no graph construction
- no UI feedback wording

#### 2. SubjectHandle

This is the stable input contract for semantic providers.

Two primary categories:

- `CodeSubjectHandle`
- `ResourceSubjectHandle`

Planned code variants:

- Java method-like subject
- Kotlin function subject
- Kotlin accessor subject
- Kotlin constructor subject

Planned resource variants:

- MyBatis XML statement
- XML config/property
- YAML/Properties config entry
- Markdown document/section
- SQL file or statement

Every handle must carry:

- stable subject id
- source kind
- file path
- caret or range information
- origin metadata required for later source mapping

#### 3. SemanticProvider

Providers are responsible for turning a subject handle into semantic units and relations.

Provider families:

- `CodeSemanticProvider`
  - `JavaCodeSemanticProvider`
  - `KotlinCodeSemanticProvider`
- `ResourceSemanticProvider`
  - `MyBatisXmlSemanticProvider`
  - `YamlPropertiesSemanticProvider`
  - `MarkdownSemanticProvider`
  - `SqlSemanticProvider`

Each provider may use PSI, light methods, or file parsing internally, but may only emit unified semantic structures.

Providers must not emit `GraphNode` or `GraphEdge`.

#### 4. SemanticAnalysisResult

This is the core truth layer of the new architecture.

It must be rich enough to support both:

- current fact-link graph behavior
- future full flowchart behavior

Required fields:

- `subject`
- `anchors`
- `semanticUnits`
- `relations`
- `diagnostics`
- `boundaries`
- `sourceMappings`

### Semantic Units

The minimum first-class units are:

- `MethodLikeUnit`
- `FlowScopeUnit`
- `FlowActionUnit`
- `InvocationUnit`
- `ResourceUnit`
- `TerminalUnit`
- `MergeUnit`

These units must be able to represent:

- code entry points
- control-flow scopes
- executable actions
- resource declarations and facts
- exit/return/throw semantics
- control-flow merge points

### Relations

The minimum first-class relations are:

- `contains`
- `controlFlow`
- `invokes`
- `references`
- `bindsTo`
- `implements`
- `documents`

The critical requirement is that control flow and invocation are distinct relation families.

The current system over-indexes on calls. The rewritten kernel must support full flow semantics, where invocation is one step within a larger flow model.

## Full Flowchart Requirement

The rewrite must explicitly support future generation of real flowcharts, not only partial call graphs.

That means `SemanticAnalysisResult` must support:

- sequential execution
- conditional branching
- labeled true/false edges
- loop-back edges
- merge/join nodes
- return edges
- throw/exception paths
- call-sites as flow actions
- resource touches embedded in flow

If these semantics are not encoded into the unified result, later "full flowchart" work would require rewriting providers again. That is not acceptable.

## AnalysisOutcome and UI Integration

`SemanticAnalysisResult` is not UI-facing. UI integration happens through a second transformation step.

### AnalysisOutcome

`AnalysisOutcome` is a presentation-oriented result derived from one semantic truth.

Required fields:

- `displayMode`
- `visibleGraph`
- `fullGraph`
- `anchorNodeId`
- `selectedMethodSignature`
- `displayName`
- `feedbackLevel`
- `feedbackMessage`
- `projectionStats`

This makes the integration boundary explicit:

`SemanticAnalysisResult -> AnalysisOutcomeFactory -> AnalysisOutcome -> applyAnalysisOutcome -> GraphEditorStateService`

### Why This Boundary Matters

This allows the UI to remain stable while the semantic kernel is rewritten.

The existing UI/state pipeline fundamentally consumes:

- `GraphDocument`
- selection state
- graph source labels
- feedback messages
- revisions

Therefore the new kernel can connect to the current UI without rewriting the frontend, as long as `AnalysisOutcome` remains a stable adapter contract.

## Presentation Modes

One semantic result must support multiple display modes.

At minimum, the rewrite must support:

- `FACT_GRAPH`
- `FLOWCHART`
- `RESOURCE_RELATION_VIEW`

This is a hard requirement, not an optional future idea.

The system must be architected so that adding or changing a presentation mode primarily affects:

- `AnalysisOutcomeFactory`
- projection policy
- frontend renderer

It must not require changing Java/Kotlin/XML/SQL semantic providers unless the semantic truth itself is incomplete.

## Policy Separation

The current system mixes multiple kinds of truncation. The rewrite will separate them into three explicit policy layers.

### 1. SemanticCapturePolicy

Controls what semantic elements providers are allowed or required to emit.

Examples:

- capture flow scopes
- capture invocation actions
- capture exception paths
- capture resource references

This policy defines semantic richness, not graph size.

### 2. TraversalBudgetPolicy

Controls how large the full semantic truth may grow during expansion.

Examples:

- max downstream depth
- max upstream depth
- max invocations per unit
- max related resources per unit

This replaces current extraction-stage budgeting with an explicit contract.

### 3. ProjectionPolicy

Controls how the full semantic truth is transformed for interactive display.

Examples:

- max visible nodes
- max visible edges
- overflow summary generation
- current-method prioritization
- flowchart-specific folding

Projection must never redefine semantic truth.

## Resource Support

Resources are first-class semantic inputs, not fallback node wrappers.

The rewrite must move resource handling out of editor-context heuristics and into provider contracts.

This means:

- XML/Markdown/SQL/YAML/Properties are represented as `ResourceSubjectHandle`
- providers emit `ResourceUnit` and `SemanticReference`
- code/resource connections are resolved by `RelationResolver`

This avoids forcing resources into a method-centric abstraction while still unifying them with code under one semantic protocol.

## RelationResolver

`RelationResolver` is the cross-provider composition layer.

Responsibilities:

- resolve code-to-code references across providers
- resolve resource-to-code anchors
- resolve interface/implementation relationships
- resolve method/document/config/sql links

This prevents each provider from having to understand every other provider's internal rules.

Without this layer, cross-language and cross-resource coupling would reappear immediately.

## GraphAssembler

`GraphAssembler` is the only place that converts semantic truth into graph-model truth.

Responsibilities:

- map semantic units to graph nodes
- map semantic relations to graph edges
- preserve source mappings and diagnostics in graph metadata
- support different assembly strategies for different display modes

Non-responsibilities:

- no PSI traversal
- no editor context lookup
- no traversal budgeting

## Existing Components: Keep, Replace, Remove

### Keep

These can remain as downstream infrastructure:

- `GraphDocument` and graph model types
- `GraphEditorStateService`
- `GraphEditorBridge`
- `GraphBrowserPanel`
- `InteractiveGraphProjector` conceptually, though its implementation may be refactored to consume explicit projection inputs
- JCEF frontend transport/state flow

### Replace

- current editor subject discovery logic
- current language/resource extraction core
- current direct PSI-to-graph path

### Remove or reduce to adapters

- `CurrentEditorGraphContextSupport`
- `JavaResolver` as the current all-in-one mixed abstraction
- `GraphExtractor` in its current form

These classes currently combine too many layers and must not remain the semantic kernel.

## Connecting to Current Service Layer

`LinkGraphProjectService` should become a thin orchestration layer around the new kernel.

The service should:

1. resolve the current editor subject
2. request semantic analysis
3. build the desired `AnalysisOutcome`
4. call `applyAnalysisOutcome`

The service should not own language-specific PSI logic.

### New Service Boundary

Recommended new internal methods:

- `analyzeCurrentSubject()`
- `analyzeSubject(handle, mode)`
- `buildAnalysisOutcome(semanticResult, mode)`
- `applyAnalysisOutcome(outcome)`

This replaces the current split between "current method graph" and "current node graph" as separate internal pipelines.

## Testing Strategy

This rewrite must be validated at four levels.

### 1. SubjectLocator tests

Validate caret-to-subject resolution for:

- Java method
- Kotlin function
- Kotlin accessor
- Kotlin constructor
- XML statement
- YAML/Properties entry
- Markdown section/document
- SQL file or statement

### 2. Provider tests

Validate each provider emits correct `SemanticAnalysisResult` fragments.

Special focus:

- Java and Kotlin parity for method-like subjects
- resource references resolving into unified semantic references
- flow semantics including branches and terminals

### 3. Assembly and presentation tests

Validate one semantic result can generate:

- fact graph
- flowchart
- resource-relation view

Validate projection behavior separately from semantic analysis.

### 4. Integration tests

Validate the new kernel connects correctly to:

- `LinkGraphProjectService`
- `GraphEditorStateService`
- `GraphEditorBridge`
- current JCEF bootstrap/state sync path

### 5. Real IDE verification

Manual `runIde` verification must cover:

- Java method analysis
- Kotlin function/accessor/constructor analysis
- XML resource analysis
- SQL analysis
- Markdown analysis
- switching display modes without re-running extraction incorrectly

## Migration Strategy

Although the chosen direction is a rewrite, the implementation should still be staged so verification stays possible.

Recommended order:

1. Introduce new semantic contracts and adapter boundary.
2. Implement `SubjectLocator` and `SubjectHandle`.
3. Implement `SemanticAnalysisResult` and relation model.
4. Implement Java and Kotlin providers first.
5. Implement `AnalysisOutcomeFactory` and connect it to current UI/state.
6. Port XML/YAML/Markdown/SQL providers.
7. Replace old extraction entrypoints in `LinkGraphProjectService`.
8. Remove legacy resolver/extractor paths.

The end state is not dual-stack compatibility. Legacy extraction code should be deleted once the new kernel is verified.

## Non-Goals

- rewriting the frontend transport protocol from scratch
- changing `GraphDocument` as the canonical UI-facing graph format in this phase
- redesigning the visual style of the canvas in this phase
- introducing LLM-dependent semantic extraction

## Risks

### Risk: semantic model is still too weak

Mitigation:

- make full flowchart support a first-class requirement now

### Risk: UI integration boundary leaks PSI concerns back in

Mitigation:

- allow `GraphEditorStateService` to receive only `AnalysisOutcome`, never PSI objects

### Risk: resource support becomes second-class again

Mitigation:

- enforce `ResourceSubjectHandle` and `ResourceSemanticProvider` from the beginning

### Risk: provider-specific metadata diverges

Mitigation:

- require all providers to emit shared semantic unit and relation types

## Final Decision

Proceed with a full rewrite of the semantic extraction kernel, but keep the existing UI/state/JCEF pipeline as downstream infrastructure.

The hard architectural commitments are:

- one semantic truth layer
- one presentation adapter layer
- multiple display modes from the same semantic result
- separate policies for semantic capture, traversal budgeting, and projection
- first-class support for both code and resource subjects
- stable integration through `AnalysisOutcome -> applyAnalysisOutcome -> GraphEditorStateService`
