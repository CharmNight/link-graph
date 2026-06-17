package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.model.GraphMetadataKeys
import com.charmnight.linkgraph.application.model.ApplicationGraphView
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.usecase.CurrentMethodNodeInput
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCase
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCaseResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubjectGraphUseCaseTest {
    @Test
    fun downgradesResourceFlowchartModeToResourceRelationView() {
        val handle = resourceHandle(ResourceSubjectKind.CONFIG_ITEM)
        val analysisResult = SemanticAnalysisResult(
            subject = handle,
            anchors = emptyList(),
            semanticUnits = emptyList(),
            relations = emptyList(),
            diagnostics = emptyList(),
            boundaries = emptyList(),
            sourceMappings = emptyList(),
        )

        val result = SubjectGraphUseCase().requestAnalysisDisplayMode(
            snapshot = WorkflowEditorSnapshot(lastGraphSource = "cachedSource"),
            displayMode = AnalysisDisplayMode.FLOWCHART,
            cachedResult = analysisResult,
            lastGraphSource = "cachedSource",
        )

        val ready = assertIs<SubjectGraphUseCaseResult.DisplayModeReady>(result)
        assertEquals(AnalysisDisplayMode.RESOURCE_RELATION_VIEW, ready.displayMode)
        assertEquals("cachedSource", ready.source)
    }

    @Test
    fun rejectsDisplayModeSwitchWhenNoSemanticAnalysisIsCached() {
        val result = SubjectGraphUseCase().requestAnalysisDisplayMode(
            snapshot = WorkflowEditorSnapshot(),
            displayMode = AnalysisDisplayMode.FLOWCHART,
            cachedResult = null,
            lastGraphSource = null,
        )

        val rejected = assertIs<SubjectGraphUseCaseResult.DisplayModeRejected>(result)
        assertEquals(ApplicationFeedbackLevel.WARNING, rejected.level)
    }

    @Test
    fun switchesLoadedProjectLevelViewsWithoutRequestingSemanticReanalysis() {
        val loadedGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "node-loaded", type = NodeType.CLASS, title = "Loaded")),
        )
        val useCase = SubjectGraphUseCase()

        listOf(
            AnalysisDisplayMode.ARCHITECTURE_GRAPH to WorkflowEditorSnapshot(
                architectureGraphView = ApplicationGraphView(visibleGraph = loadedGraph),
            ),
            AnalysisDisplayMode.CLASS_DIAGRAM to WorkflowEditorSnapshot(
                classDiagramView = ApplicationGraphView(visibleGraph = loadedGraph),
            ),
            AnalysisDisplayMode.REVIEW_GRAPH to WorkflowEditorSnapshot(
                reviewGraphView = ApplicationGraphView(visibleGraph = loadedGraph),
            ),
        ).forEach { (displayMode, snapshot) ->
            val result = useCase.requestAnalysisDisplayMode(
                snapshot = snapshot,
                displayMode = displayMode,
                cachedResult = null,
                lastGraphSource = null,
            )

            val requested = assertIs<SubjectGraphUseCaseResult.RequestedDisplayMode>(result)
            assertEquals(displayMode, requested.displayMode)
        }
    }

    @Test
    fun rejectsUnloadedReviewGraphSwitchWithoutFallingBackToSemanticCache() {
        val result = SubjectGraphUseCase().requestAnalysisDisplayMode(
            snapshot = WorkflowEditorSnapshot(),
            displayMode = AnalysisDisplayMode.REVIEW_GRAPH,
            cachedResult = null,
            lastGraphSource = null,
        )

        val rejected = assertIs<SubjectGraphUseCaseResult.DisplayModeRejected>(result)
        assertEquals(ApplicationFeedbackLevel.INFO, rejected.level)
        assertEquals("Review Graph 尚未加载，请通过 Review Graph 入口构建项目级索引。", rejected.message)
    }

    @Test
    fun appliesCurrentMethodNodeWithCanvasPositionWithoutMutatingSnapshot() {
        val existingGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "node-existing", type = NodeType.METHOD, title = "existing")),
        )
        val snapshot = WorkflowEditorSnapshot(
            currentSceneId = GraphSceneId.WORKSPACE_FACT,
            factGraphView = ApplicationGraphView(visibleGraph = existingGraph),
        )

        val result = SubjectGraphUseCase().applyCurrentMethodNode(
            snapshot = snapshot,
            currentMethodNode = CurrentMethodNodeInput(
                node = GraphNode(id = "method:run", type = NodeType.METHOD, title = "run"),
                methodSignature = "com.example.Service.run():void",
                methodDisplayName = "run",
            ),
        )

        val applied = assertIs<SubjectGraphUseCaseResult.CurrentMethodNodeApplied>(result)
        val addedNode = applied.graph.nodes.single { it.id == "method:run" }
        assertEquals("380", addedNode.metadata[GraphMetadataKeys.Ui.X])
        assertEquals("96", addedNode.metadata[GraphMetadataKeys.Ui.Y])
        assertEquals("com.example.Service.run():void", applied.selectedMethodSignature)
        assertEquals(listOf("node-existing"), snapshot.factGraphView.visibleGraph.nodes.map { it.id })
    }

    private fun resourceHandle(kind: ResourceSubjectKind): ResourceSubjectHandle {
        return ResourceSubjectHandle(
            subjectId = "resource",
            sourcePath = "src/main/resources/application.yml",
            sourceRange = SourceRange(0, 8, 1, 1),
            displayName = "feature.flag",
            kind = kind,
        )
    }
}
