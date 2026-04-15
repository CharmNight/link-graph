package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProvider
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.services.LinkGraphProjectService
import com.charmnight.linkgraph.workbench.WorkbenchLayoutPreferencesService
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class GraphEditorBridgeTest : BasePlatformTestCase() {
    fun testCurrentStateHydratesPersistentWorkbenchPreferencesIntoRuntimeSnapshot() {
        val stateService = project.getService(GraphEditorStateService::class.java)
        val preferencesService = project.getService(WorkbenchLayoutPreferencesService::class.java)
        preferencesService.update("audit.request-status", true)
        stateService.markWorkbenchSectionPreferences(emptyMap())

        val snapshot = GraphEditorBridge(project).currentState()

        assertEquals(true, snapshot.workbenchSectionPreferences["audit.request-status"])
        assertEquals(true, stateService.snapshot().workbenchSectionPreferences["audit.request-status"])
    }

    fun testDispatchCurrentEditorContextGraphUsesEditorContextWorkflow() {
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                当前链路入口
            """.trimIndent(),
        )
        val projectService = project.getService(LinkGraphProjectService::class.java)
        val resourceHandle = ResourceSubjectHandle(
            subjectId = "resource-markdown:order-flow-md",
            sourcePath = "order-flow.md",
            sourceRange = SourceRange(startOffset = 0, endOffset = 10, startLine = 1, endLine = 1),
            displayName = "order-flow.md",
            kind = ResourceSubjectKind.MARKDOWN_PAGE,
            attributes = mapOf("path" to "order-flow.md"),
        )
        projectService.testSubjectLocatorOverride = object : SubjectLocator {
            override fun locate(
                project: Project,
                editor: Editor?,
                commitDocument: Boolean,
            ): SubjectHandle = resourceHandle

            override fun previewKind(
                project: Project,
                editor: Editor?,
                commitDocument: Boolean,
            ): SubjectPreviewKind = SubjectPreviewKind.RESOURCE_SUBJECT
        }
        projectService.testSemanticAnalyzerOverride = SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(
                    object : SemanticProvider {
                        override fun supports(handle: SubjectHandle): Boolean = handle is ResourceSubjectHandle

                        override fun analyze(
                            handle: SubjectHandle,
                            capturePolicy: SemanticCapturePolicy,
                            budgetPolicy: TraversalBudgetPolicy,
                        ): SemanticAnalysisResult {
                            val resource = handle as ResourceSubjectHandle
                            return SemanticAnalysisResult(
                                subject = resource,
                                anchors = listOf(SemanticAnchor(id = "anchor-doc", targetUnitId = "resource:doc")),
                                semanticUnits = listOf(
                                    ResourceUnit(
                                        id = "resource:doc",
                                        title = "order-flow.md",
                                        resourceKind = "MARKDOWN_PAGE",
                                        metadata = mapOf("path" to "order-flow.md"),
                                    ),
                                    MethodLikeUnit(
                                        id = "method:submit",
                                        title = "OrderService.submit",
                                        signature = "com.example.OrderService.submit(java.lang.String):java.lang.String",
                                    ),
                                ),
                                relations = listOf(
                                    SemanticRelation(
                                        kind = SemanticRelationKind.DOCUMENTS,
                                        fromUnitId = "resource:doc",
                                        toUnitId = "method:submit",
                                    ),
                                ),
                                diagnostics = emptyList(),
                                boundaries = emptyList(),
                                sourceMappings = listOf(
                                    SourceMapping(
                                        sourcePath = "order-flow.md",
                                        sourceRange = resource.sourceRange,
                                        targetUnitId = "resource:doc",
                                    ),
                                ),
                            )
                        }
                    },
                ),
            ),
        )

        GraphEditorBridge(project).dispatch(GraphEditorMessage.RequestCurrentEditorContextGraph)

        val snapshot = waitForSnapshot { current ->
            current.lastGraphSource == "currentContext" &&
                current.analysisDisplayMode == AnalysisDisplayMode.FACT_GRAPH &&
                current.visibleGraph?.nodes?.any { node ->
                    node.type == NodeType.DOC_PAGE && node.title == "order-flow.md"
                } == true
        }

        assertEquals("currentContext", snapshot.lastGraphSource)
        assertTrue(snapshot.visibleGraph?.nodes?.any { it.title == "OrderService.submit" } == true)
    }

    private fun waitForSnapshot(
        predicate: (GraphEditorStateService.Snapshot) -> Boolean,
    ): GraphEditorStateService.Snapshot {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (predicate(snapshot)) {
                return snapshot
            }
            Thread.sleep(50)
        }
        fail("等待桥接后的编辑器上下文快照收敛超时")
        throw IllegalStateException("unreachable")
    }
}
