package com.charmnight.linkgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class CoreShellArchitectureTest {
    private val projectRoot = Path.of("").toAbsolutePath()

    @Test
    fun graphBrowserPanelDelegatesPayloadParsingAndDebugProbe() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPanel.kt")

        assertTrue(
            tokenize(source).none {
                it in setOf(
                    "parseQuestionWithIds(",
                    "parseGenerationPlanDiscussionPayload(",
                    "parseAuditRequestPayload(",
                    "parseResolveInvestigationThreadPayload(",
                    "parseBeautificationPayload(",
                    "parseLayoutPositions(",
                    "buildRuntimeProbeScript(",
                    "private fun configureBrowser(",
                    "private fun buildBridgeScript(",
                    "addLoadHandler(",
                    "addDisplayHandler(",
                )
            },
            "GraphBrowserPanel must delegate payload parsing, bridge registration, debug probe generation, and browser lifecycle wiring",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPayloadParser.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserDebugProbe.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserBridgeRegistrar.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserLifecycle.kt")
    }

    @Test
    fun graphEditorStateServiceKeepsModelsOutsideTheServiceFile() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt")

        assertTrue(
            tokenize(source).none {
                it in setOf(
                    "data class AsyncRequestState(",
                    "data class Snapshot(",
                    "data class RuntimeArtifactSummary(",
                    "data class SourceNavigationState(",
                    "data class OperationFeedback(",
                    "private fun preservedConfirmedDraftState(",
                    "private fun reapplyConfirmedDraftGraph(",
                    "private fun buildOutcomeViewDocuments(",
                )
            },
            "GraphEditorStateService must keep models and graph mutation builders outside the service file",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateModels.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorAsyncRequestState.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorGraphMutations.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorAsyncRequestMutations.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorWorkbenchMutations.kt")
    }

    @Test
    fun graphEditorStateServiceDelegatesAsyncAndWorkbenchMutationEntrypoints() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt")

        assertTrue(
            tokenize(source).none {
                it in setOf(
                    "fun markAuditResult(",
                    "fun beginAuditRequest(",
                    "fun markAuditRequestFailed(",
                    "fun markDiffReviewResult(",
                    "fun beginDiffReviewRequest(",
                    "fun markDiffReviewRequestFailed(",
                    "fun markGraphBeautificationResult(",
                    "fun beginGraphBeautificationRequest(",
                    "fun markGraphBeautificationRequestFailed(",
                    "fun markGenerationPlan(",
                    "fun beginGenerationPlanRequest(",
                    "fun markGenerationPlanRequestFailed(",
                    "fun markGenerationPlanDiscussion(",
                    "fun beginGenerationPlanDiscussionRequest(",
                    "fun markGenerationPlanDiscussionRequestFailed(",
                    "fun markGeneratedCodeDrafts(",
                    "fun beginCodeDraftRequest(",
                    "fun markCodeDraftRequestFailed(",
                    "fun markDraftWorkbenchState(",
                    "fun markDraftValidationState(",
                    "fun markCodeEligibilityDecision(",
                    "fun markWorkbenchSectionPreferences(",
                    "fun markOperationFeedback(",
                    "fun markArtifactContents(",
                )
            },
            "GraphEditorStateService must delegate async-request and workbench mutation entrypoints to dedicated support objects",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorGraphStateSupport.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorAsyncRequestStateSupport.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorWorkbenchStateSupport.kt")
    }

    @Test
    fun linkGraphProjectServiceDelegatesConfirmedDraftWorkflowAndDiagnostics() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt")

        assertTrue(
            tokenize(source).none {
                it in setOf(
                    "private fun rebuildConfirmedDraftGraph(",
                    "private fun observedNodeIds(",
                    "private fun candidateBelongsToSelectedMethod(",
                    "private fun graphContainsMethodSignature(",
                    "private fun resolveCandidateMethodSignatures(",
                    "private fun resolveNodeMethodSignature(",
                    "private fun logGraphDiagnostics(",
                    "internal fun prepareDebugRequestedAnalysisDisplayModeIfPresent(",
                    "private fun resolveDebugRequestedAnalysisDisplayMode(",
                    "internal fun loadDebugGraph(",
                    "internal fun loadDebugMethodGraphBySignatureAsync(",
                )
            },
            "LinkGraphProjectService must delegate confirmed-draft logic, diagnostics helpers, and debug workflows",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/services/ConfirmedDraftChangeWorkflow.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/services/GraphDiagnosticsLogger.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/services/ProjectDebugWorkflow.kt")
    }

    @Test
    fun linkGraphProjectServiceDelegatesGraphEditorBridgeCommands() {
        val projectService = read("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt")
        val bridge = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorBridge.kt")

        assertTrue(
            tokenize(projectService).none {
                it in setOf(
                    "fun loadGraph(",
                    "fun handleFrontendGraphChanged(",
                    "fun handleFrontendLayoutChanged(",
                    "fun requestSourceNavigation(",
                    "fun requestAuditAsync(",
                    "fun requestDiffReviewAsync(",
                    "fun requestGraphBeautificationAsync(",
                    "fun requestGenerationPlanAsync(",
                    "fun requestGenerationPlanDiscussionAsync(",
                    "fun requestCodeDraftsAsync(",
                    "fun applyCodeDrafts(",
                    "fun applySingleCodeDraft(",
                    "fun openCodeDraftNativeDiff(",
                    "fun requestDraftNavigation(",
                    "fun updateWorkbenchSectionPreference(",
                )
            },
            "LinkGraphProjectService must move graph-editor bridge commands into a dedicated router/support object",
        )
        assertTrue(
            bridge.contains("GraphEditorCommandRouter"),
            "GraphEditorBridge must depend on the dedicated graph-editor command router",
        )
        assertTrue(
            bridge.contains("project.getService(GraphEditorCommandRouter::class.java)"),
            "GraphEditorBridge must resolve the graph-editor command router from the project service container",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorCommandRouter.kt")
    }

    @Test
    fun linkGraphProjectServiceDelegatesRuntimeAndSettingsHelpers() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt")

        assertTrue(
            tokenize(source).none {
                it in setOf(
                    "private val runtimeTraceEnabled: Boolean =",
                    "private fun <T> computeOnIdeThread(action: () -> T): T {",
                    "private fun stateService(): GraphEditorStateService {",
                    "private fun effectiveGenerationSettings() = testEffectiveGenerationSettingsOverride",
                    "private fun runtimeTrace(message: () -> String) {",
                )
            },
            "LinkGraphProjectService must delegate runtime tracing, IDE-thread execution, and effective settings resolution to dedicated support objects",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectRuntimeSupport.kt")
    }

    @Test
    fun graphEditorBridgeUsesSingleCommandRouterFacade() {
        val bridge = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorBridge.kt")
        val router = read("src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorCommandRouter.kt")

        assertTrue(
            tokenize(bridge).none {
                it.contains("LinkGraphProjectService") ||
                    it.contains("private val projectService:") ||
                    it.contains("projectService.")
            },
            "GraphEditorBridge must not keep a parallel LinkGraphProjectService entrypoint once GraphEditorCommandRouter exists",
        )
        assertTrue(
            bridge.contains("commandRouter.dispatch(message)"),
            "GraphEditorBridge must delegate graph-editor command dispatch through GraphEditorCommandRouter",
        )
        assertTrue(
            router.contains("fun dispatch(message: GraphEditorMessage)"),
            "GraphEditorCommandRouter must provide the single graph-editor command dispatch entrypoint",
        )
    }

    @Test
    fun draftNavigationUsesProjectScopedNavigationBoundary() {
        val generationWorkflow = read("src/main/kotlin/com/charmnight/linkgraph/services/GenerationWorkflow.kt")
        val sourceNavigation = read("src/main/kotlin/com/charmnight/linkgraph/navigation/SourceNavigationService.kt")

        assertTrue(
            !generationWorkflow.contains("navigateToPath(targetPath)"),
            "GenerationWorkflow must not forward frontend draft-navigation paths into the generic SourceNavigationService path opener",
        )
        assertTrue(
            sourceNavigation.contains("fun navigateToProjectPath("),
            "SourceNavigationService must expose a project-scoped draft navigation API instead of reusing the unrestricted path opener",
        )
    }

    @Test
    fun sourceNavigationUsesTrustedNavigationBoundaryAndFrontendGraphSanitizer() {
        val projectService = read("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt")
        val graphWorkspaceWorkflow = read("src/main/kotlin/com/charmnight/linkgraph/services/GraphWorkspaceWorkflow.kt")

        assertTrue(
            projectService.contains("navigationNodeFinder = ::findTrustedNavigationNode"),
            "SourceNavigationWorkflow must resolve bridge node-navigation requests from the trusted navigation index only",
        )
        assertTrue(
            graphWorkspaceWorkflow.contains("frontendGraphMutationSanitizer.sanitize"),
            "GraphWorkspaceWorkflow must sanitize frontend graph mutations before persisting them into editor state",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/services/FrontendGraphMutationSanitizer.kt")
    }

    @Test
    fun appShellDelegatesBootstrapAndGraphEditControllers() {
        val source = read("web/src/app/App.tsx")

        assertTrue(
            tokenize(source).none {
                it in setOf(
                    "function applyBootstrapState(",
                    "function syncGraph(",
                    "function handleRequestAudit(",
                    "function handleRequestGraphBeautification(",
                    "function handleConfirmCandidateChange(",
                    "function handleAddExplanationNoteToDraft(",
                    "function handleSelectDraftEntry(",
                    "function handleLocateDraftChangeNode(",
                    "function handleOpenDraftNote(",
                    "function handleLocateDraftNoteNode(",
                    "function resolveInitialState(",
                    "function resolveVisibleGraph(",
                    "function resolveWorkingGraph(",
                    "function resolveReferenceWorkingGraph(",
                    "function resolveReferenceFactGraph(",
                    "function resolveDesignBaselineGraph(",
                    "function resolveSourceNavigationState(",
                    "function resolveFactGraphView(",
                    "function resolveFlowchartView(",
                    "function resolveResourceRelationView(",
                    "function resolveGraphPatchNodeIds(",
                    "function resolveDraftEntryTargetNodeIds(",
                    "function resolveNodeOwnerSignature(",
                    "function overlayDraftEntryOntoFlowchartView(",
                    "function deriveFactGraphSummary(",
                    "function deriveFlowchartSummary(",
                    "function deriveResourceRelationSummary(",
                    "function resolveAnchorNodeId(",
                    "function syncFactGraphViewDocument(",
                    "function applyLayoutUpdatesToGraphDocument(",
                    "function handleRequestArtifact(",
                    "function handleConfirmImportMermaid(",
                    "function handleRequestDiffReview(",
                    "function handleWorkbenchSectionPreferenceChange(",
                    "function handleWriteSingleCodeDraft(",
                    "function handleOpenCodeDraftNativeDiff(",
                    "function handleOpenAudit(",
                    "function handleOpenDraftValidation(",
                    "function handleRequestGenerationPlan(",
                    "function handleRequestCodeDrafts(",
                    "function handleRequestGenerationPlanDiscussion(",
                    "function handleRequestScopedAudit(",
                    "function handleConfirmImportMermaidDraft(",
                    "function handleExpandOverflowNode(",
                    "function handleFocusDiffItem(",
                    "function syncManualNodeIdCounters(",
                    "function maybeCompleteSourceNavigationProbe(",
                    "requestArtifactContent(",
                    "requestDiffReviewAsync(",
                    "applySingleCodeDraft(",
                    "openCodeDraftNativeDiff(",
                    "updateWorkbenchSectionPreference(",
                    "importMermaid(",
                )
            },
            "App.tsx must delegate bootstrap fixtures, bridge actions, graph edit, and workbench command flows to dedicated modules",
        )
        assertExists("web/src/app/controllers/useBootstrapProjectionState.ts")
        assertExists("web/src/app/controllers/useGraphEditController.ts")
        assertExists("web/src/app/controllers/useDraftWorkbenchController.ts")
        assertExists("web/src/app/controllers/useAppBridgeController.ts")
        assertExists("web/src/app/controllers/useAppWorkbenchShellController.ts")
        assertExists("web/src/app/controllers/useInteractionProbeController.ts")
        assertExists("web/src/app/sampleState.ts")
        assertExists("web/src/app/appGraphSupport.ts")
    }

    private fun read(relativePath: String): String = Files.readString(projectRoot.resolve(relativePath))

    private fun assertExists(relativePath: String) {
        assertTrue(
            Files.exists(projectRoot.resolve(relativePath)),
            "expected file to exist: $relativePath",
        )
    }

    private fun tokenize(source: String): List<String> = source.lineSequence().map(String::trim).toList()
}
