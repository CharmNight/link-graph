package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.model.ApplicationGraphView
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
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
        assertEquals("380", addedNode.metadata["ui.x"])
        assertEquals("96", addedNode.metadata["ui.y"])
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
