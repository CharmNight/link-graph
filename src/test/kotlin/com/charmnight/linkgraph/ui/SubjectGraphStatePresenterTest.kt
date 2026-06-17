package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class SubjectGraphStatePresenterTest {
    @Test
    fun mapsFeedbackDisplayModeAndSelectedMethod() {
        val stateService = GraphEditorStateService()
        var browserSyncCount = 0
        val presenter = SubjectGraphStatePresenter(stateService) {
            browserSyncCount += 1
        }

        presenter.presentFeedback(ApplicationFeedbackLevel.WARNING, "需要重新分析")
        presenter.presentAnalysisDisplayMode(AnalysisDisplayMode.FACT_GRAPH)
        presenter.presentSelectedMethod("com.example.Foo.bar()")

        val snapshot = stateService.snapshot()
        assertEquals(ApplicationFeedbackLevel.WARNING, snapshot.operationFeedback?.level)
        assertEquals("需要重新分析", snapshot.operationFeedback?.message)
        assertEquals(AnalysisDisplayMode.FACT_GRAPH, snapshot.analysisDisplayMode)
        assertEquals("com.example.Foo.bar()", snapshot.selectedMethodSignature)
        assertEquals(3, browserSyncCount)
    }

    @Test
    fun mapsAnalysisOutcomeAndResourceSelection() {
        val stateService = GraphEditorStateService()
        var browserSyncCount = 0
        val presenter = SubjectGraphStatePresenter(stateService) {
            browserSyncCount += 1
        }
        val node = GraphNode(
            id = "node-1",
            type = NodeType.METHOD,
            title = "Foo.bar",
            signature = "com.example.Foo.bar()",
        )
        val graph = GraphDocument(nodes = listOf(node))
        val outcome = AnalysisOutcome(
            displayMode = AnalysisDisplayMode.FLOWCHART,
            visibleGraph = graph,
            fullGraph = graph,
            selectedMethodSignature = "com.example.Foo.bar()",
            displayName = "Foo.bar",
            feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
            statusMessage = "分析完成",
        )

        presenter.presentAnalysisOutcome(outcome, "currentMethod")
        presenter.presentResourceNodeAdded(
            selectedNodeId = "node-1",
            statusMessage = "已追加当前资源节点：Foo.bar",
        )

        val snapshot = stateService.snapshot()
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertEquals("node-1", snapshot.currentSceneState().selectedNodeId)
        assertEquals("已追加当前资源节点：Foo.bar", snapshot.operationFeedback?.message)
        assertEquals(2, browserSyncCount)
    }

    @Test
    fun mapsDebugGraphProjectionAndFeedback() {
        val stateService = GraphEditorStateService()
        var browserSyncCount = 0
        val presenter = SubjectGraphStatePresenter(stateService) {
            browserSyncCount += 1
        }
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method-1",
                    type = NodeType.METHOD,
                    title = "Foo.bar",
                    signature = "com.example.Foo.bar()",
                ),
            ),
        )

        presenter.presentDebugGraphLoaded(
            graph = graph,
            source = "debug:wide19",
            selectedMethodSignature = "com.example.Foo.bar()",
            summary = "wide graph",
        )

        val snapshot = stateService.snapshot()
        assertEquals("debug:wide19", snapshot.lastGraphSource)
        assertEquals("com.example.Foo.bar()", snapshot.selectedMethodSignature)
        assertEquals("已自动载入诊断链路图：wide graph", snapshot.operationFeedback?.message)
        assertEquals(1, browserSyncCount)
    }
}
