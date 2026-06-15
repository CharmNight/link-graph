package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphStateArchitectureGateTest {
    private val root: Path = Path.of("").toAbsolutePath()

    private fun read(relativePath: String): String = Files.readString(root.resolve(relativePath))
    private fun ktFilesUnder(path: Path): List<Path> {
        if (!Files.exists(path)) {
            return emptyList()
        }
        return Files.walk(path)
            .filter { candidate -> candidate.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
    }

    private fun sourceBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) {
            return ""
        }
        val end = source.indexOf("\n)\n", start).takeIf { it >= 0 } ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun stateModelUsesCanonicalWorkspaceGraphsAndSceneStateOnly() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateModels.kt")
        val commandModelSource = read("src/main/kotlin/com/charmnight/linkgraph/application/model/GraphEditorCommandModels.kt")
        val snapshotSource = sourceBlock(source, "data class GraphEditorStateSnapshot(")

        assertTrue(snapshotSource.contains("val semanticFactGraph: GraphDocument"))
        assertTrue(snapshotSource.contains("val workspaceBaseGraph: GraphDocument"))
        assertTrue(snapshotSource.contains("val workspaceGraph: GraphDocument"))
        assertTrue(commandModelSource.contains("enum class GraphSceneId"))
        assertTrue(source.contains("typealias GraphSceneId = com.charmnight.linkgraph.application.model.GraphSceneId"))
        assertTrue(snapshotSource.contains("val currentSceneId: GraphSceneId"))
        assertTrue(snapshotSource.contains("val sceneStates: Map<GraphSceneId, GraphSceneState>"))
        assertFalse(snapshotSource.contains("val visibleGraph: GraphDocument?"))
        assertFalse(snapshotSource.contains("val workingGraph: GraphDocument?"))
        assertFalse(snapshotSource.contains("val referenceWorkingGraph: GraphDocument?"))
        assertFalse(snapshotSource.contains("val referenceFactGraph: GraphDocument?"))
        assertFalse(snapshotSource.contains("val selectedNodeId: String?"))
        assertFalse(snapshotSource.contains("val layoutState: GraphLayoutState"))
        assertFalse(snapshotSource.contains("val diffMode: Boolean"))
    }

    @Test
    fun stateModelExposesConcernSpecificDomainStatesForRenderingAndTransport() {
        val source = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateModels.kt")
        val renderer = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorPageRenderer.kt")
        val freezer = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateFreezer.kt")
        val snapshotSource = sourceBlock(source, "data class GraphEditorStateSnapshot(")

        listOf(
            "data class WorkspaceState(",
            "data class GraphViewsState(",
            "data class AssistantState(",
            "data class ReviewState(",
            "data class GenerationState(",
            "data class NavigationState(",
            "data class TransportState(",
            "data class GraphEditorDomainStates(",
            "fun GraphEditorStateSnapshot.domainStates(): GraphEditorDomainStates",
        ).forEach { fragment ->
            assertTrue(source.contains(fragment), "Graph editor state model must expose domain state: $fragment")
        }

        assertTrue(
            renderer.contains("val domainStates = snapshot.domainStates()"),
            "Page renderer should read state through domainStates() instead of depending only on the flat snapshot.",
        )
        assertTrue(
            freezer.contains("val domainStates = domainStates()"),
            "Freezer should freeze via domain state boundaries so copied state stays grouped by concern.",
        )
        listOf(
            "val visibleGraph: GraphDocument?",
            "val workingGraph: GraphDocument?",
            "val referenceWorkingGraph: GraphDocument?",
            "val selectedNodeId: String?",
            "val layoutState: GraphLayoutState",
        ).forEach { forbidden ->
            assertFalse(
                snapshotSource.contains(forbidden),
                "Domain state split must not reintroduce old flat compatibility fields: $forbidden",
            )
        }
    }

    @Test
    fun legacyWholeSnapshotAndViewGraphMutationPathsAreRemoved() {
        val stateServiceSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorStateService.kt")
        val graphSupportSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorGraphStateSupport.kt")
        val workflowSource = read("src/main/kotlin/com/charmnight/linkgraph/application/workflow/GraphWorkspaceWorkflow.kt")

        assertFalse(stateServiceSource.contains("replaceSnapshot("))
        assertFalse(stateServiceSource.contains("newDraftMutationContext("))
        assertFalse(Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/services/ProjectEditorSession.kt")))
        assertFalse(graphSupportSource.contains("markViewGraphChanged"))
        assertFalse(workflowSource.contains("handleFrontendGraphChanged("))
        assertFalse(Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/services/GraphEditorStateSyncSession.kt")))
    }

    @Test
    fun bridgeProtocolRemovesLegacyWholeGraphChangedMessage() {
        val messageSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorMessage.kt")
        val bridgeSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserBridgeRegistrar.kt")
        val routerSource = read("src/main/kotlin/com/charmnight/linkgraph/ui/GraphEditorCommandRouter.kt")

        assertFalse(messageSource.contains("data class GraphChanged"))
        assertFalse(bridgeSource.contains("graphChangedQuery"))
        assertFalse(bridgeSource.contains("GraphEditorMessage.GraphChanged"))
        assertFalse(routerSource.contains("handleFrontendGraphChanged"))
        assertTrue(messageSource.contains("data class ApplyGraphEditScript"))
        assertTrue(bridgeSource.contains("private val bridgeCommandQuery: JBCefJSQuery"))
        assertTrue(bridgeSource.contains("applyGraphEditScript: (payload) => sendCommand(\"applyGraphEditScript\""))
        assertTrue(routerSource.contains("ApplyGraphEditScript"))
    }

    @Test
    fun serviceWorkflowsDoNotMutateEditorStateDirectly() {
        val servicesRoot = root.resolve("src/main/kotlin/com/charmnight/linkgraph/services")
        val forbiddenFragments = listOf(
            "session.mutate",
            "session.mutateBatch",
            "GraphEditorStateMutationContext",
        )
        val offenders = ktFilesUnder(servicesRoot)
            .flatMap { path ->
                val source = Files.readString(path)
                forbiddenFragments.mapNotNull { fragment ->
                    if (source.contains(fragment)) {
                        "${root.relativize(path)} contains $fragment"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "Workflow and application services must project UI changes through presenters/reducers only: ${offenders.joinToString()}",
        )
    }

    @Test
    fun serviceWorkflowsDoNotBindToUiStateSnapshotsOrSessions() {
        val servicesRoot = root.resolve("src/main/kotlin/com/charmnight/linkgraph/services")
        val allowedFiles = setOf(
            servicesRoot.resolve("ApplicationSnapshotAdapter.kt").normalize(),
            servicesRoot.resolve("ToolGraphSnapshotAdapter.kt").normalize(),
            servicesRoot.resolve("LinkGraphProjectRuntimeSupport.kt").normalize(),
        )
        val workflowNamePattern = Regex("""(Workflow|Commands|Components|CommandRouter)\.kt$""")
        val forbiddenFragments = listOf(
            "ProjectEditorSession",
            "GraphEditorStateSnapshot",
            "GraphEditorStateService",
            "currentStateService",
            "session.snapshot",
        )
        val offenders = ktFilesUnder(servicesRoot)
            .filter { path -> workflowNamePattern.containsMatchIn(path.fileName.toString()) }
            .filter { path -> path.normalize() !in allowedFiles }
            .flatMap { path ->
                val source = Files.readString(path)
                forbiddenFragments.mapNotNull { fragment ->
                    if (source.contains(fragment)) {
                        "${root.relativize(path)} contains $fragment"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "Business workflows must use application snapshots/ports instead of UI state/session types: ${offenders.joinToString()}",
        )
    }

    @Test
    fun applicationServicesDoNotMutateUiStateDirectly() {
        val applicationRoot = root.resolve("src/main/kotlin/com/charmnight/linkgraph/application")
        val forbiddenFragments = listOf(
            "GraphEditorStateService",
            "stateService()",
            ".tryCommit(",
            "withWorkspaceGraphChanged",
            "toWorkflowEditorSnapshot",
            "toToolGraphSnapshot",
            "stateService().graph",
            "stateService().workbench",
            "stateService().asyncRequests",
            "GraphEditorStateMutationContext",
            "session.mutate",
            "session.mutateBatch",
        )
        val offenders = Files.walk(applicationRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                val source = Files.readString(path)
                forbiddenFragments.mapNotNull { fragment ->
                    if (source.contains(fragment)) {
                        "${root.relativize(path)} contains $fragment"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "application services must not mutate UI state directly; projection belongs in UI presenters/reducers: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun applicationPackageDoesNotImportUi() {
        val applicationRoot = root.resolve("src/main/kotlin/com/charmnight/linkgraph/application")
        val offenders = Files.walk(applicationRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                Files.readString(path).lineSequence().mapIndexedNotNull { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import com.charmnight.linkgraph.ui")) {
                        "${root.relativize(path)}:${index + 1}: $trimmed"
                    } else {
                        null
                    }
                }.toList()
            }

        assertTrue(
            offenders.isEmpty(),
            "application must not import UI packages; UI snapshot adapters, presenters, and state models belong outside application: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun applicationWorkflowsReturnResultsInsteadOfCallingPresentationPorts() {
        val workflowRoot = root.resolve("src/main/kotlin/com/charmnight/linkgraph/application/workflow")
        val forbiddenPatterns = listOf(
            Regex("""\bStatePort\b""") to "StatePort",
            Regex("""\bpresenterProvider\b""") to "presenterProvider",
            Regex("""\bgenerationStatePresenter\b""") to "generationStatePresenter",
            Regex("""\.\s*present[A-Za-z0-9_]*\s*\(""") to ".present*(",
        )
        val offenders = Files.walk(workflowRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                val source = Files.readString(path)
                forbiddenPatterns.mapNotNull { (pattern, label) ->
                    if (pattern.containsMatchIn(source)) {
                        "${root.relativize(path)} contains $label"
                    } else {
                        null
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "application workflows/use cases must return result/event objects; UI presenters/reducers own projection: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun applicationPortsDoNotOwnUiPresenterInterfaces() {
        val portRoot = root.resolve("src/main/kotlin/com/charmnight/linkgraph/application/port")
        val offenders = Files.walk(portRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                Files.readString(path).lineSequence().mapIndexedNotNull { index, line ->
                    if (Regex("""\binterface\s+\w*StatePort\b""").containsMatchIn(line)) {
                        "${root.relativize(path)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }.toList()
            }

        assertTrue(
            offenders.isEmpty(),
            "UI presenter/reducer interfaces belong in UI; application should expose result/event models only: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun applicationPortsDoNotDefinePresentationDtos() {
        val portRoot = root.resolve("src/main/kotlin/com/charmnight/linkgraph/application/port")
        val offenders = Files.walk(portRoot)
            .filter { path -> path.toString().endsWith(".kt") }
            .use { paths -> paths.toList() }
            .flatMap { path ->
                Files.readString(path).lineSequence().mapIndexedNotNull { index, line ->
                    if (Regex("""\b(data\s+class|class|interface)\s+\w*Presentation\b""").containsMatchIn(line)) {
                        "${root.relativize(path)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }.toList()
            }

        assertTrue(
            offenders.isEmpty(),
            "application/port should expose application result DTOs, not UI presentation DTOs: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun applicationEventsDoNotCarryUiPresentationState() {
        val eventSource = read("src/main/kotlin/com/charmnight/linkgraph/application/event/GraphEditorApplicationEvent.kt")
        val eventBlock = sourceBlock(eventSource, "sealed interface GraphEditorApplicationEvent")
        val forbiddenFragments = listOf(
            "feedbackMessage",
            "preserveLastMessageType",
            "ViewDocument",
        )
        val offenders = forbiddenFragments.filter(eventBlock::contains)

        assertTrue(
            offenders.isEmpty(),
            "GraphEditorApplicationEvent should carry application results/status only, not UI presentation state: " +
                offenders.joinToString(),
        )
    }

    @Test
    fun allBusinessChainsUseUseCaseResultPattern() {
        val requiredUseCases = mapOf(
            "generation" to "GenerationUseCase",
            "review" to "ReviewUseCase",
            "subject" to "SubjectGraphUseCase",
            "workspace" to "WorkspaceGraphUseCase",
            "navigation" to "SourceNavigationUseCase",
        )
        val missingUseCaseFiles = requiredUseCases.values
            .map { className -> "src/main/kotlin/com/charmnight/linkgraph/application/usecase/$className.kt" }
            .filterNot { relativePath -> Files.exists(root.resolve(relativePath)) }
        assertTrue(
            missingUseCaseFiles.isEmpty(),
            "4.3 requires every business chain to expose pure application use cases: ${missingUseCaseFiles.joinToString()}",
        )

        val missingUseCaseTests = requiredUseCases.values
            .map { className -> "src/test/kotlin/com/charmnight/linkgraph/application/${className}Test.kt" }
            .filterNot { relativePath -> Files.exists(root.resolve(relativePath)) }
        assertTrue(
            missingUseCaseTests.isEmpty(),
            "4.3 requires pure use-case result tests for every business chain: ${missingUseCaseTests.joinToString()}",
        )

        requiredUseCases.forEach { (workflowName, className) ->
            val workflowPaths = when (workflowName) {
                "generation" -> listOf(
                    "src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/GenerationPlanWorkflow.kt",
                    "src/main/kotlin/com/charmnight/linkgraph/application/workflow/generation/CodeDraftGenerationWorkflow.kt",
                )
                "review" -> listOf("src/main/kotlin/com/charmnight/linkgraph/application/workflow/ReviewWorkflow.kt")
                "subject" -> listOf("src/main/kotlin/com/charmnight/linkgraph/application/workflow/SubjectGraphWorkflow.kt")
                "workspace" -> listOf("src/main/kotlin/com/charmnight/linkgraph/application/workflow/GraphWorkspaceWorkflow.kt")
                "navigation" -> listOf("src/main/kotlin/com/charmnight/linkgraph/application/workflow/SourceNavigationWorkflow.kt")
                else -> error("Unknown workflow $workflowName")
            }.map(root::resolve)
            val delegatesToUseCase = workflowPaths.any { workflowPath ->
                Files.exists(workflowPath) && Files.readString(workflowPath).contains(className)
            }
            assertTrue(
                delegatesToUseCase,
                "${workflowPaths.joinToString()} must delegate business decisions to $className instead of keeping them only in workflow code",
            )
        }
    }

    @Test
    fun projectEditorSessionBelongsToUiInternalsNotServices() {
        assertFalse(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/services/ProjectEditorSession.kt")),
            "ProjectEditorSession is a UI transaction primitive and must not remain in services",
        )
    }

    @Test
    fun servicesPackageDoesNotOwnBusinessWorkflows() {
        val servicesRoot = root.resolve("src/main/kotlin/com/charmnight/linkgraph/services")
        val allowedWorkflowFiles = setOf(
            "AsyncRequestLifecycleSupport.kt",
            "AsyncRequestTracker.kt",
        )
        val offenders = ktFilesUnder(servicesRoot)
            .filter { path -> path.toString().endsWith("Workflow.kt") }
            .filterNot { path -> path.fileName.toString() in allowedWorkflowFiles }
            .map { path -> root.relativize(path).toString() }

        assertTrue(
            offenders.isEmpty(),
            "services must not own business workflow classes after application extraction: ${offenders.joinToString()}",
        )
    }

    @Test
    fun testsDoNotKeepLegacySnapshotCompatibilityAccessors() {
        val testAdapterSources = listOf(
            "src/test/kotlin/com/charmnight/linkgraph/testing/GraphEditorTestAdapters.kt",
            "src/integrationTest/kotlin/com/charmnight/linkgraph/ui/LinkGraphToolWindowIT.kt",
        ).associateWith(::read)

        val forbiddenFragments = listOf(
            "val GraphEditorStateSnapshot.visibleGraph",
            "private val GraphEditorStateSnapshot.visibleGraph",
            "val GraphEditorStateSnapshot.workingGraph",
            "private val GraphEditorStateSnapshot.workingGraph",
            "val GraphEditorStateSnapshot.referenceWorkingGraph",
            "val GraphEditorStateSnapshot.referenceFactGraph",
            "private val GraphEditorStateSnapshot.referenceFactGraph",
            "fun GraphEditorStateService.markWorkingGraphChanged",
            "fun GraphEditorStateService.markViewGraphChanged",
        )
        val offenders = testAdapterSources.flatMap { (relativePath, source) ->
            forbiddenFragments.mapNotNull { fragment ->
                if (source.contains(fragment)) "$relativePath contains $fragment" else null
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "Tests should use scene/view documents and production projection adapters instead of legacy snapshot compatibility helpers: " +
                offenders.joinToString(),
        )
    }
}
