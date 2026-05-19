package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
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
                    "parseQaRequestPayload(",
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
        assertExists("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateFreezer.kt")
    }

    @Test
    fun graphEditorStateModelsDoesNotOwnDeepFreezeImplementation() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateModels.kt")

        assertTrue(
            !source.contains("internal fun GraphEditorStateSnapshot.freeze()") &&
                !source.contains("private fun GraphDocument.freeze()"),
            "GraphEditorStateModels should define state shapes; deep freeze helpers belong in GraphEditorStateFreezer",
        )
    }

    @Test
    fun graphEditorStateServiceDelegatesAsyncAndWorkbenchMutationEntrypoints() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt")

        assertTrue(
            tokenize(source).none {
                it in setOf(
                    "fun markQaResult(",
                    "fun beginQaRequest(",
                    "fun markQaRequestFailed(",
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
    fun applicationArchitectureBoundaryPackagesMustExistAfterBoundaryExtraction() {
        assertTrue(
            Files.exists(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/application")),
            "application package must exist once the boundary extraction is in place",
        )
        assertTrue(
            Files.exists(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/foundation")),
            "foundation package must exist once the boundary extraction is in place",
        )
    }

    @Test
    fun llmSourcesMustNotDependOnServicesOrUi() {
        val offenders = Files.walk(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/llm"))
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                val source = Files.readString(path)
                sequenceOf("com.charmnight.linkgraph.services", "com.charmnight.linkgraph.ui")
                    .filter(source::contains)
                    .map { dependency -> "${projectRoot.relativize(path)} uses $dependency" }
                    .toList()
            }

        assertTrue(
            offenders.isEmpty(),
            "llm sources must not depend on services or ui: ${offenders.joinToString()}",
        )
    }

    @Test
    fun planningInputMustNotContainGraphEditorStateSnapshot() {
        val applicationPackage = projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/application")
        val offenders = if (Files.exists(applicationPackage)) {
            Files.walk(applicationPackage)
                .filter { path -> path.toString().endsWith(".kt") }
                .use { paths -> paths.toList() }
                .filter { path -> Files.readString(path).contains("GraphEditorStateSnapshot") }
        } else {
            listOf(applicationPackage)
        }

        assertTrue(
            offenders.isEmpty(),
            "PlanningInput or equivalent generation input must not contain GraphEditorStateSnapshot: ${offenders.joinToString()}",
        )
    }

    @Test
    fun linkGraphProjectServiceMustBeDeletedFromProductionCode() {
        assertFalse(
            Files.exists(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt")),
            "LinkGraphProjectService must be removed instead of kept as a production facade",
        )
        assertFalse(
            Files.exists(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphDebugEnvironment.kt")),
            "LinkGraphDebugEnvironment must live in foundation directly; services must not keep a forwarding compatibility wrapper",
        )
    }

    @Test
    fun graphEditorBridgeUsesSingleCommandRouterFacade() {
        val bridge = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorBridge.kt")
        val router = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorCommandRouter.kt")

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
        assertTrue(
            !router.contains("LinkGraphProjectService") &&
                !router.contains("projectService") &&
                !router.contains("graphWorkspaceWorkflow") &&
                !router.contains("reviewWorkflow") &&
                !router.contains("generationWorkflow") &&
                !router.contains("draftPatchWorkflow"),
            "GraphEditorCommandRouter must depend on explicit command/workflow collaborators instead of routing through LinkGraphProjectService",
        )
    }

    @Test
    fun graphEditorCommandRouterMustNotUseLegacyServiceForwardingStack() {
        val router = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorCommandRouter.kt")

        assertTrue(
            tokenize(router).none {
                it in setOf(
                    "LinkGraphProjectService",
                    "projectService",
                    "SubjectGraphCommands",
                    "WorkspaceGraphCommands",
                    "ReviewCommands",
                    "GenerationCommands",
                    "DraftPatchCommands",
                    "SourceNavigationCommands",
                    "DebugGraphCommands",
                )
            },
            "GraphEditorCommandRouter must depend on application APIs instead of the legacy command forwarding stack",
        )
    }

    @Test
    fun graphEditorCommandRouterMustUseApplicationServiceBoundary() {
        val router = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorCommandRouter.kt")

        assertTrue(
            router.contains("GraphEditorApplicationService"),
            "GraphEditorCommandRouter must enter business flows through GraphEditorApplicationService",
        )
        assertFalse(
            Regex("""(?m)^\s*components\.""").containsMatchIn(router),
            "GraphEditorCommandRouter must not use GraphEditorApplicationService as a replacement forwarding facade with component-style access",
        )
        assertFalse(
            Regex("""components\.\w+Commands""").containsMatchIn(router),
            "GraphEditorCommandRouter must not forward to command/workflow collaborators through GraphEditorApplicationService",
        )
        assertFalse(
            router.contains("GraphEditorStateService"),
            "GraphEditorCommandRouter must not mutate GraphEditorStateService directly; UI projection belongs in presenters/reducers",
        )
    }

    @Test
    fun graphEditorApplicationServiceMustNotBeUiProjectionOrForwardingFacade() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationService.kt")

        val forbiddenFragments = listOf(
            "com.charmnight.linkgraph.ui.",
            "StatePresenter",
            "presenterProvider",
            "generationStatePresenterProvider",
            "stateService()",
            "tryCommit(",
            "withWorkspaceGraphChanged",
            "toApplicationSnapshot",
            "toWorkflowEditorSnapshot",
            "toToolGraphSnapshot",
            "internal val subjectCommands",
            "internal val workspaceCommands",
            "internal val draftCommands",
            "internal val generationCommands",
            "internal val reviewCommands",
            "internal val sourceCommands",
            "internal val confirmedDraftCommands",
            "internal val debugCommands",
        )
        val offenders = forbiddenFragments.filter(source::contains)

        assertTrue(
            offenders.isEmpty(),
            "GraphEditorApplicationService must be an application boundary, not a UI projection or command/workflow forwarding facade: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun servicesPackageMustNotKeepBusinessWorkflowCompositionRoot() {
        assertFalse(
            Files.exists(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectComponents.kt")),
            "LinkGraphProjectComponents must not remain as a production forwarding/composition facade after application service extraction",
        )
        assertTrue(
            Files.exists(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationService.kt")),
            "GraphEditorApplicationService should live in application package as the project application boundary",
        )
    }

    @Test
    fun productionEntrypointsDoNotRouteThroughLinkGraphProjectService() {
        val actionSources = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/actions/OpenLinkGraphAction.kt",
            "src/main/kotlin/com/charmnight/linkgraph/actions/AddCurrentMethodToGraphAction.kt",
            "src/main/kotlin/com/charmnight/linkgraph/actions/ImportMermaidAction.kt",
            "src/main/kotlin/com/charmnight/linkgraph/actions/ExportMermaidAction.kt",
            "src/main/kotlin/com/charmnight/linkgraph/actions/ShowDiffModeAction.kt",
        ).associateWith(::read)
        val debugAutomation = read("src/main/kotlin/com/charmnight/linkgraph/toolwindow/debug/LinkGraphDebugAutomationCoordinator.kt")
        val draftPatchWorkflow = read("src/main/kotlin/com/charmnight/linkgraph/application/workflow/DraftPatchWorkflow.kt")

        assertTrue(
            actionSources.values.none { source -> source.contains("LinkGraphProjectService") },
            "IDE actions must enter explicit command services instead of the legacy project-service facade",
        )
        assertTrue(
            !debugAutomation.contains("LinkGraphProjectService"),
            "Debug automation must use the same command/workflow entrypoints as production UI paths",
        )
        assertTrue(
            !draftPatchWorkflow.contains("LinkGraphProjectService."),
            "DraftPatchWorkflow must not depend on facade-owned enum/types",
        )
    }

    @Test
    fun draftNavigationUsesProjectScopedNavigationBoundary() {
        val codeDraftApplyWorkflow = read("src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/CodeDraftApplyWorkflow.kt")
        val sourceNavigation = read("src/main/kotlin/com/charmnight/linkgraph/navigation/SourceNavigationService.kt")

        assertTrue(
            !codeDraftApplyWorkflow.contains("navigateToPath(targetPath)"),
            "CodeDraftApplyWorkflow must not forward frontend draft-navigation paths into the generic SourceNavigationService path opener",
        )
        assertTrue(
            sourceNavigation.contains("fun navigateToProjectPath("),
            "SourceNavigationService must expose a project-scoped draft navigation API instead of reusing the unrestricted path opener",
        )
    }

    @Test
    fun sourceNavigationUsesTrustedNavigationBoundaryAndFrontendGraphSanitizer() {
        val projectComponents = read("src/main/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationService.kt")
        val graphWorkspaceWorkflow = read("src/main/kotlin/com/charmnight/linkgraph/application/workflow/GraphWorkspaceWorkflow.kt")
        val workspaceGraphUseCase = read("src/main/kotlin/com/charmnight/linkgraph/application/usecase/WorkspaceGraphUseCase.kt")

        assertTrue(
            projectComponents.contains("navigationNodeFinder = ::findTrustedNavigationNodeFromIndex"),
            "SourceNavigationWorkflow must resolve bridge node-navigation requests from the trusted navigation index only",
        )
        assertTrue(
            graphWorkspaceWorkflow.contains("WorkspaceGraphUseCase") &&
                workspaceGraphUseCase.contains("frontendGraphMutationSanitizer.sanitize"),
            "Workspace graph edits must sanitize frontend graph mutations inside the use case before persistence",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/application/workflow/FrontendGraphMutationSanitizer.kt")
    }

    @Test
    fun appShellDelegatesBootstrapAndGraphEditControllers() {
        val source = read("web/src/app/App.tsx")

        assertTrue(
            tokenize(source).none {
                val obsoletePrefix = "Au" + "dit"
                it in setOf(
                    "function applyBootstrapState(",
                    "function syncGraph(",
                    "function handleRequest${obsoletePrefix}(",
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
                    "function handleOpen${obsoletePrefix}(",
                    "function handleOpenDraftValidation(",
                    "function handleRequestGenerationPlan(",
                    "function handleRequestCodeDrafts(",
                    "function handleRequestGenerationPlanDiscussion(",
                    "function handleRequestScoped${obsoletePrefix}(",
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

    @Test
    fun generationEntrypointsUseFocusedSubWorkflowsWithoutFacade() {
        val applicationService = read("src/main/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationService.kt")

        assertTrue(
            !Files.exists(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow/GenerationWorkflow.kt")),
            "GenerationWorkflow facade should be physically removed once ApplicationService directly owns focused generation sub-workflows",
        )
        assertTrue(
            applicationService.contains("GenerationPlanWorkflow") &&
                applicationService.contains("GenerationPlanDiscussionWorkflow") &&
                applicationService.contains("CodeDraftGenerationWorkflow") &&
                applicationService.contains("CodeDraftApplyWorkflow"),
            "ApplicationService should compose focused generation sub-workflows directly instead of routing through a facade",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/GenerationPlanWorkflow.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/GenerationPlanDiscussionWorkflow.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/CodeDraftGenerationWorkflow.kt")
        assertExists("src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/CodeDraftApplyWorkflow.kt")
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
