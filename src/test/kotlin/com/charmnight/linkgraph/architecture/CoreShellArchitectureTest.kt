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
    fun graphBrowserPanelLoadsSameOriginEntryUrlInsteadOfInlineFileDocument() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPanel.kt")

        assertTrue(
            source.contains("loadURL(entryUrl)"),
            "GraphBrowserPanel must load the same-origin entry URL so Vite module, CSS, dynamic import, and Worker requests do not resolve under file:///jbcefbrowser",
        )
        assertFalse(
            source.contains("loadHTML("),
            "loadHTML makes JCEF expose the document as file:///jbcefbrowser with origin null, breaking module/CSS asset loading",
        )
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
    fun applicationDoesNotDependOnViewDocumentsOrViewPackages() {
        val applicationPackage = projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/application")
        val offenders = Files.walk(applicationPackage)
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                val source = Files.readString(path)
                buildList {
                    source.lineSequence().forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("import ") && Regex("""\.view\.""").containsMatchIn(trimmed)) {
                            add("${projectRoot.relativize(path)}:${index + 1}: $trimmed")
                        }
                    }
                    if (source.contains("ViewDocument")) {
                        add("${projectRoot.relativize(path)} contains ViewDocument")
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "application code should depend on application result models rather than UI/view documents: " +
                offenders.joinToString(),
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
        assertFalse(
            bridge.contains("fun loadGraph(") ||
                bridge.contains("兼容旧接口"),
            "GraphEditorBridge must not expose compatibility command helpers outside dispatch(GraphEditorMessage).",
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
    fun applicationCommandsUseTypedNamedHandlerBeans() {
        val commandFiles = listOf(
            "ApplicationCommand.kt",
            "ApplicationCommandDispatcher.kt",
            "ApplicationCommandHandlers.kt",
            "AssistantTaskExecutor.kt",
            "AssistantApplicationCommandHandler.kt",
            "SubjectApplicationCommandHandler.kt",
            "IndexedGraphApplicationCommandHandler.kt",
            "WorkspaceApplicationCommandHandler.kt",
            "SourceNavigationApplicationCommandHandler.kt",
            "ReviewApplicationCommandHandler.kt",
            "DraftApplicationCommandHandler.kt",
            "GenerationApplicationCommandHandler.kt",
            "DebugApplicationCommandHandler.kt",
        )
        commandFiles.forEach { fileName ->
            assertExists("src/main/kotlin/com/charmnight/linkgraph/application/command/$fileName")
        }

        val command = read("src/main/kotlin/com/charmnight/linkgraph/application/command/ApplicationCommand.kt")
        val dispatcher = read("src/main/kotlin/com/charmnight/linkgraph/application/command/ApplicationCommandDispatcher.kt")
        val handlers = read("src/main/kotlin/com/charmnight/linkgraph/application/command/ApplicationCommandHandlers.kt")
        val composition = read("src/main/kotlin/com/charmnight/linkgraph/application/composition/ApplicationCommandComposition.kt")
        val commandPackage = commandFiles.joinToString("\n") { fileName ->
            read("src/main/kotlin/com/charmnight/linkgraph/application/command/$fileName")
        }

        assertTrue(command.contains("fun dispatchTo(handlers: ApplicationCommandHandlers): R"))
        assertTrue(dispatcher.contains("command.dispatchTo(handlers)"))
        assertTrue(composition.contains("ApplicationCommandHandlers("))
        listOf(
            "val subject:",
            "val indexedGraph:",
            "val workspace:",
            "val sourceNavigation:",
            "val assistant:",
            "val review:",
            "val draft:",
            "val generation:",
            "val debug:",
        ).forEach { namedField -> assertTrue(handlers.contains(namedField)) }
        listOf(
            "subject = subject",
            "indexedGraph = indexedGraph",
            "workspace = workspace",
            "sourceNavigation = sourceNavigation",
            "assistant = assistant",
            "review = review",
            "draft = draft",
            "generation = generation",
            "debug = debug",
        ).forEach { namedBean -> assertTrue(composition.contains(namedBean)) }

        assertFalse(
            Files.exists(projectRoot.resolve(
                "src/main/kotlin/com/charmnight/linkgraph/application/command/ApplicationCommandHandler.kt",
            )),
            "旧责任链接口文件必须在类型化迁移后删除",
        )
        listOf(
            "canHandle(",
            "ApplicationCommand<*>): Any?",
            "UNCHECKED_CAST",
            "firstOrNull { candidate",
            "Map<KClass",
        ).forEach { forbidden ->
            assertFalse(commandPackage.contains(forbidden), "typed command dispatch must not contain $forbidden")
        }

        val router = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorCommandRouter.kt")
        assertTrue(
            router.contains("ApplicationCommand") && router.contains("commandDispatcher.dispatch"),
            "GraphEditorCommandRouter should dispatch typed application commands.",
        )

        val service = read("src/main/kotlin/com/charmnight/linkgraph/application/GraphEditorApplicationService.kt")
        assertTrue(
            service.contains("val commandDispatcher") || service.contains("fun commandDispatcher("),
            "GraphEditorApplicationService should expose an ApplicationCommandDispatcher boundary.",
        )
        val forwardingEntrypoints = listOf(
            "fun loadGraph(",
            "fun importMermaid(",
            "fun exportMermaid(",
            "fun showDiffMode(",
            "fun requestQaAsync(",
            "fun retryLastQaRequestAsync(",
            "fun requestDiffReviewAsync(",
            "fun requestGraphBeautificationAsync(",
            "fun requestGenerationPlanAsync(",
            "fun requestCodeDraftsAsync(",
            "fun applyCodeDrafts(",
            "fun requestIndexedGraph(",
        )
        val offenders = forwardingEntrypoints.filter(service::contains)
        assertTrue(
            offenders.isEmpty(),
            "GraphEditorApplicationService must not remain a broad public command-forwarding facade: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun assistantTaskCommandsMustRequireConcreteActionId() {
        val applicationCommand = read("src/main/kotlin/com/charmnight/linkgraph/application/command/ApplicationCommand.kt")
        val graphEditorMessage = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorMessage.kt")
        val bridgeParser = read("src/main/kotlin/com/charmnight/linkgraph/ui/bridge/BridgeCommandParser.kt")

        listOf(applicationCommand, graphEditorMessage, bridgeParser).forEach { source ->
            assertFalse(
                source.contains("AssistantActionId.fromIntent(intent)") ||
                    source.contains("enumOrDefault(\"actionId\""),
                "assistant task routing must require a concrete AssistantActionId instead of inferring user actions from AssistantIntent",
            )
        }
        assertTrue(
            applicationCommand.contains("val actionId: AssistantActionId") &&
                graphEditorMessage.contains("val actionId: AssistantActionId") &&
                bridgeParser.contains("payload.enum<AssistantActionId>(\"actionId\")"),
            "assistant task contracts should make actionId explicit at every boundary",
        )
    }

    @Test
    fun applicationEventsAndResultsLiveOutsidePortProviderFile() {
        listOf(
            "src/main/kotlin/com/charmnight/linkgraph/application/event/GraphEditorApplicationEvent.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/result/GenerationResults.kt",
            "src/main/kotlin/com/charmnight/linkgraph/application/result/ReviewResults.kt",
        ).forEach(::assertExists)

        val portSource = read("src/main/kotlin/com/charmnight/linkgraph/application/port/StatePresentationPorts.kt")
        listOf(
            "sealed interface GraphEditorApplicationEvent",
            "data class GenerationPlanResult",
            "data class QaCompletedResult",
            "data class DiffReviewCompletedResult",
            "data class BeautificationCompletedResult",
        ).forEach { fragment ->
            assertFalse(
                portSource.contains(fragment),
                "application/port should define ports only; event/result models belong in application/event and application/result: $fragment",
            )
        }
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
        val workflowComposition = read("src/main/kotlin/com/charmnight/linkgraph/application/composition/WorkflowComposition.kt")
        val graphWorkspaceWorkflow = read("src/main/kotlin/com/charmnight/linkgraph/application/workflow/GraphWorkspaceWorkflow.kt")
        val workspaceGraphUseCase = read("src/main/kotlin/com/charmnight/linkgraph/application/usecase/WorkspaceGraphUseCase.kt")
        val graphEditApplier = read("src/main/kotlin/com/charmnight/linkgraph/application/edit/GraphEditApplier.kt")

        assertTrue(
            workflowComposition.contains("navigationNodeFinder = ::findTrustedNavigationNodeFromIndex"),
            "SourceNavigationWorkflow must resolve bridge node-navigation requests from the trusted navigation index only",
        )
        assertTrue(
            graphWorkspaceWorkflow.contains("WorkspaceGraphUseCase") &&
                workspaceGraphUseCase.contains("GraphEditApplier") &&
                graphEditApplier.contains("frontendGraphMutationSanitizer.sanitize"),
            "Workspace graph edits must sanitize frontend graph mutations inside the graph edit applier before persistence",
        )
        assertExists("src/main/kotlin/com/charmnight/linkgraph/application/workflow/FrontendGraphMutationSanitizer.kt")
    }

    @Test
    fun appShellDelegatesBootstrapAndGraphEditControllers() {
        val source = read("web/src/app/App.tsx")

        assertTrue(
            tokenize(source).none {
                val obsoletePrefix = "Au" + "dit"
                val exactForbiddenLines = setOf(
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
                    "requestDiffReviewAsync(",
                    "applySingleCodeDraft(",
                    "openCodeDraftNativeDiff(",
                    "importMermaid(",
                )
                it in exactForbiddenLines ||
                    it.startsWith("function handleRequestArtifact(") ||
                    it.contains("requestArtifactContent(") ||
                    it.contains("requestArtifactContent }") ||
                    it.contains("requestArtifactContent,")
            },
            "App.tsx must delegate bootstrap fixtures, bridge actions, graph edit, and workbench command flows to dedicated modules",
        )
        assertExists("web/src/app/controllers/useBootstrapProjectionState.ts")
        assertExists("web/src/app/controllers/useGraphEditController.ts")
        assertExists("web/src/app/controllers/useAppBridgeController.ts")
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

    @Test
    fun productionJsonSerializationUsesCanonicalCodec() {
        val allowed = setOf(
            projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph/json/JsonCodec.kt").normalize(),
        )
        val forbiddenFragments = listOf(
            "private class JsonParser",
            "private fun appendJsonValue",
            "private fun escape(",
            "internal fun toJson(value: Any?)",
        )
        val offenders = Files.walk(projectRoot.resolve("src/main/kotlin/com/charmnight/linkgraph"))
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .filterNot { path -> path.normalize() in allowed }
            .flatMap { path ->
                val source = Files.readString(path)
                forbiddenFragments.mapNotNull { fragment ->
                    if (source.contains(fragment)) {
                        "${projectRoot.relativize(path)} contains $fragment"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "production JSON serialization/parsing must go through JsonCodec instead of hand-written codecs: ${offenders.joinToString()}",
        )
    }

    @Test
    fun classUsageAndAssistantSessionPayloadMappersUseDtosInsteadOfMaps() {
        // P2-6: UI 层 payload mapper / renderer / assembler 已全部迁移到 DTO，
        // 不应再出现主动构造 Map<String, Any?> 的代码（linkedMapOf("key" to value) 模式）。
        // 注：注释里的「linkedMapOf」「Map<String, Any?>」描述历史改动，不算违规。
        val offenders = listOf(
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorClassUsagePayloadMappers.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorAssistantSessionRenderer.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRendererHelpers.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRenderer.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/BootstrapPayloadAssembler.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorArtifactSlicePayloadBuilder.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPresentationPayloadMappers.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/PageRendererDtos.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorTransportSliceRenderer.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorTransportEnvelope.kt",
            "src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserTransportDispatcher.kt",
        ).map { path ->
            val raw = read(path)
            // 去掉行注释
            val withoutLineComments = raw.lineSequence()
                .map { it.substringBefore("//") }
                .joinToString("\n")
            // 去掉块注释（KDoc /* ... */）
            val withoutBlockComments = withoutLineComments
                .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            path to withoutBlockComments
        }.filter { (_, code) ->
            code.contains("Map<String, Any?>") || code.contains("linkedMapOf(")
        }.map { (path, _) -> path }

        assertTrue(
            offenders.isEmpty(),
            "DTO 化后的 mapper 不应再主动构造 Map<String, Any?>（应使用 data class）：${offenders.joinToString()}",
        )
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
