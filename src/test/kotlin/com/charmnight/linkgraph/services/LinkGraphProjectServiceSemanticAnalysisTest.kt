package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.model.TerminalUnit
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProvider
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkGraphProjectServiceSemanticAnalysisTest : BasePlatformTestCase() {
    fun testRequestAnalysisDisplayModeRebuildsFromCachedSemanticResult() {
        myFixture.configureByText(
            "DemoService.java",
            """
                package com.example;

                class DemoService {
                    String run(String value) {
                        return <caret>value.trim();
                    }
                }
            """.trimIndent(),
        )

        val analyzerCallCount = AtomicInteger(0)
        val provider = object : SemanticProvider {
            override fun supports(handle: SubjectHandle): Boolean = handle is CodeSubjectHandle

            override fun analyze(
                handle: SubjectHandle,
                capturePolicy: SemanticCapturePolicy,
                budgetPolicy: TraversalBudgetPolicy,
            ): SemanticAnalysisResult {
                analyzerCallCount.incrementAndGet()
                val codeHandle = handle as CodeSubjectHandle
                return SemanticAnalysisResult(
                    subject = codeHandle,
                    anchors = listOf(
                        SemanticAnchor(
                            id = "anchor-entry",
                            targetUnitId = "method:demo-run",
                            label = "入口",
                        ),
                    ),
                    semanticUnits = listOf(
                        MethodLikeUnit(
                            id = "method:demo-run",
                            title = "DemoService.run",
                            signature = codeHandle.methodSignature,
                        ),
                        FlowActionUnit(
                            id = "action:trim",
                            title = "value.trim()",
                            actionKind = "ACTION",
                        ),
                        TerminalUnit(
                            id = "terminal:return",
                            title = "返回",
                            terminalKind = "RETURN",
                        ),
                        ResourceUnit(
                            id = "resource:doc",
                            title = "demo-flow.md",
                            resourceKind = "MARKDOWN_PAGE",
                        ),
                    ),
                    relations = listOf(
                        SemanticRelation(SemanticRelationKind.CONTAINS, "method:demo-run", "action:trim"),
                        SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:trim", "terminal:return"),
                        SemanticRelation(SemanticRelationKind.DOCUMENTS, "resource:doc", "method:demo-run"),
                    ),
                    diagnostics = emptyList(),
                    boundaries = emptyList(),
                    sourceMappings = listOf(
                        SourceMapping(
                            sourcePath = "src/DemoService.java",
                            sourceRange = codeHandle.sourceRange,
                            targetUnitId = "method:demo-run",
                        ),
                    ),
                )
            }
        }

        val service = project.getService(LinkGraphProjectService::class.java)
        service.testSemanticAnalyzerOverride = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(provider)),
        )
        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FACT_GRAPH)

        service.loadCurrentMethodGraphAsync()
        waitForSnapshot { snapshot ->
            analyzerCallCount.get() == 1 &&
                snapshot.analysisDisplayMode == AnalysisDisplayMode.FACT_GRAPH &&
                snapshot.visibleGraph?.nodes?.any { node -> node.id == "action:trim" } == true &&
                snapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.DOC_PAGE } == true
        }

        val stateService = project.getService(GraphEditorStateService::class.java)
        val factSnapshot = stateService.snapshot()
        assertEquals(1, analyzerCallCount.get())
        assertTrue(factSnapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.FLOW_ACTION } == true)
        assertTrue(factSnapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.DOC_PAGE } == true)

        service.requestAnalysisDisplayMode(AnalysisDisplayMode.RESOURCE_RELATION_VIEW)
        waitForSnapshot { snapshot ->
            analyzerCallCount.get() == 1 &&
                snapshot.analysisDisplayMode == AnalysisDisplayMode.RESOURCE_RELATION_VIEW &&
                snapshot.visibleGraph?.nodes?.none { node -> node.type == NodeType.FLOW_ACTION } == true &&
                snapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.DOC_PAGE } == true
        }

        val resourceSnapshot = stateService.snapshot()
        assertEquals(1, analyzerCallCount.get())
        assertEquals(2, resourceSnapshot.visibleGraph?.nodes?.size)
        assertTrue(resourceSnapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.METHOD } == true)
        assertTrue(resourceSnapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.DOC_PAGE } == true)
    }

    fun testRequestAnalysisDisplayMode在脏编辑态下复用当前视图文档() {
        myFixture.configureByText(
            "DemoService.java",
            """
                package com.example;

                class DemoService {
                    String run(String value) {
                        return <caret>value.trim();
                    }
                }
            """.trimIndent(),
        )

        val analyzerCallCount = AtomicInteger(0)
        val provider = object : SemanticProvider {
            override fun supports(handle: SubjectHandle): Boolean = handle is CodeSubjectHandle

            override fun analyze(
                handle: SubjectHandle,
                capturePolicy: SemanticCapturePolicy,
                budgetPolicy: TraversalBudgetPolicy,
            ): SemanticAnalysisResult {
                analyzerCallCount.incrementAndGet()
                val codeHandle = handle as CodeSubjectHandle
                return SemanticAnalysisResult(
                    subject = codeHandle,
                    anchors = listOf(SemanticAnchor(id = "anchor-entry", targetUnitId = "method:demo-run")),
                    semanticUnits = listOf(
                        MethodLikeUnit(
                            id = "method:demo-run",
                            title = "DemoService.run",
                            signature = codeHandle.methodSignature,
                        ),
                        FlowActionUnit(
                            id = "action:trim",
                            title = "value.trim()",
                            actionKind = "ACTION",
                        ),
                        TerminalUnit(
                            id = "terminal:return",
                            title = "返回",
                            terminalKind = "RETURN",
                        ),
                    ),
                    relations = listOf(
                        SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "method:demo-run", "action:trim"),
                        SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:trim", "terminal:return"),
                    ),
                    diagnostics = emptyList(),
                    boundaries = emptyList(),
                    sourceMappings = listOf(
                        SourceMapping(
                            sourcePath = "src/DemoService.java",
                            sourceRange = codeHandle.sourceRange,
                            targetUnitId = "method:demo-run",
                        ),
                    ),
                )
            }
        }

        val service = project.getService(LinkGraphProjectService::class.java)
        service.testSemanticAnalyzerOverride = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(provider)),
        )
        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)

        service.loadCurrentMethodGraphAsync()
        waitForSnapshot { snapshot ->
            analyzerCallCount.get() == 1 &&
                snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART &&
                snapshot.visibleGraph?.nodes?.any { node -> node.id == "action:trim" } == true
        }

        val stateService = project.getService(GraphEditorStateService::class.java)
        val flowchartSnapshot = stateService.snapshot()
        stateService.markGraphChanged(
            graph = flowchartSnapshot.visibleGraph!!.copy(
                nodes = flowchartSnapshot.visibleGraph!!.nodes + GraphNode(
                    id = "design:manual-step",
                    type = NodeType.METHOD,
                    title = "ManualFlowStep",
                    sourceTag = GraphSourceTag.DRAFT_MANUAL,
                ),
            ),
        )

        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FACT_GRAPH)
        waitForSnapshot { snapshot ->
            snapshot.analysisDisplayMode == AnalysisDisplayMode.FACT_GRAPH &&
                snapshot.visibleGraph?.nodes?.none { node -> node.id == "design:manual-step" } == true
        }

        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        waitForSnapshot { snapshot ->
            snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART &&
                snapshot.visibleGraph?.nodes?.any { node -> node.id == "design:manual-step" } == true
        }

        assertEquals(1, analyzerCallCount.get())
    }

    fun testFlowchartDisplayModeLinearizesOrderedStepsFromCachedSemanticResult() {
        myFixture.configureByText(
            "DemoService.java",
            """
                package com.example;

                class DemoService {
                    void run(String value) {
                        <caret>stepA(value);
                    }

                    void stepA(String value) {}
                }
            """.trimIndent(),
        )

        val provider = object : SemanticProvider {
            override fun supports(handle: SubjectHandle): Boolean = handle is CodeSubjectHandle

            override fun analyze(
                handle: SubjectHandle,
                capturePolicy: SemanticCapturePolicy,
                budgetPolicy: TraversalBudgetPolicy,
            ): SemanticAnalysisResult {
                val codeHandle = handle as CodeSubjectHandle
                return SemanticAnalysisResult(
                    subject = codeHandle,
                    anchors = listOf(
                        SemanticAnchor(
                            id = "anchor-entry",
                            targetUnitId = "method:demo-run",
                            label = "入口",
                        ),
                    ),
                    semanticUnits = listOf(
                        MethodLikeUnit(
                            id = "method:demo-run",
                            title = "DemoService.run",
                            signature = codeHandle.methodSignature,
                        ),
                        FlowActionUnit(
                            id = "action:validate",
                            title = "validate(value)",
                            actionKind = "ACTION",
                        ),
                        FlowActionUnit(
                            id = "action:normalize",
                            title = "normalize(value)",
                            actionKind = "ACTION",
                        ),
                        FlowActionUnit(
                            id = "action:write",
                            title = "write(value)",
                            actionKind = "ACTION",
                        ),
                    ),
                    relations = listOf(
                        SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "method:demo-run", "action:validate"),
                        SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:validate", "action:normalize"),
                        SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:normalize", "action:write"),
                    ),
                    diagnostics = emptyList(),
                    boundaries = emptyList(),
                    sourceMappings = listOf(
                        SourceMapping(
                            sourcePath = "src/DemoService.java",
                            sourceRange = codeHandle.sourceRange,
                            targetUnitId = "method:demo-run",
                        ),
                    ),
                )
            }
        }

        val service = project.getService(LinkGraphProjectService::class.java)
        service.testSemanticAnalyzerOverride = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(provider)),
        )
        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FACT_GRAPH)

        service.loadCurrentMethodGraphAsync()
        waitForSnapshot { snapshot ->
            snapshot.analysisDisplayMode == AnalysisDisplayMode.FACT_GRAPH &&
                snapshot.visibleGraph?.nodes?.any { node -> node.id == "action:write" } == true
        }

        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        waitForSnapshot { snapshot ->
            snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART &&
                snapshot.visibleGraph?.edges?.any { edge ->
                    edge.type == EdgeType.CONTROL_FLOW &&
                        edge.fromNodeId == "action:validate" &&
                        edge.toNodeId == "action:normalize"
                } == true
        }

        val flowchartSnapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertTrue(
            flowchartSnapshot.visibleGraph?.edges?.any { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == "method:demo-run" &&
                    edge.toNodeId == "action:validate"
            } == true,
        )
        assertTrue(
            flowchartSnapshot.visibleGraph?.edges?.any { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == "action:validate" &&
                    edge.toNodeId == "action:normalize"
            } == true,
        )
        assertTrue(
            flowchartSnapshot.visibleGraph?.edges?.any { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == "action:normalize" &&
                    edge.toNodeId == "action:write"
            } == true,
        )
        assertTrue(
            flowchartSnapshot.visibleGraph?.edges?.none { edge ->
                edge.type == EdgeType.CONTROL_FLOW &&
                    edge.fromNodeId == "method:demo-run" &&
                    edge.toNodeId == "action:normalize"
            } == true,
        )
    }

    fun testAddCurrentEditorContextNodeUsesLocatedCodeSubjectHandle() {
        myFixture.configureByText(
            "OrderService.java",
            """
                package com.example;

                class OrderService {
                    void pla<caret>ce(String value) {
                        System.out.println(value);
                    }
                }
            """.trimIndent(),
        )
        val codeHandle = assertInstanceOf(
            com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator().locate(project, myFixture.editor),
            CodeSubjectHandle::class.java,
        )
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                当前不是方法上下文。
            """.trimIndent(),
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        service.testSubjectLocatorOverride = object : com.charmnight.linkgraph.semantic.subject.SubjectLocator {
            override fun locate(
                project: com.intellij.openapi.project.Project,
                editor: com.intellij.openapi.editor.Editor?,
                commitDocument: Boolean,
            ): SubjectHandle = codeHandle
        }

        val appended = service.addCurrentEditorContextNode()

        assertTrue(appended)
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertTrue(
            snapshot.visibleGraph?.nodes?.any { node ->
                node.type == NodeType.METHOD && node.title == "OrderService.place"
            } == true,
        )
    }

    fun testRequestExpandOverflowNodeRerunsSemanticAnalysisWithoutLegacyGraphExtractor() {
        myFixture.configureByText(
            "DemoService.java",
            """
                package com.example;

                class DemoService {
                    String run(String value) {
                        return <caret>value.trim();
                    }
                }
            """.trimIndent(),
        )
        val analyzerCallCount = AtomicInteger(0)
        val service = project.getService(LinkGraphProjectService::class.java)
        service.testSemanticAnalyzerOverride = SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(semanticProvider { codeHandle, budgetPolicy ->
                    analyzerCallCount.incrementAndGet()
                    val expanded = budgetPolicy.maxInvocationsPerUnit > 5 || budgetPolicy.maxDownstreamDepth > 2
                    SemanticAnalysisResult(
                        subject = codeHandle,
                        anchors = listOf(SemanticAnchor(id = "anchor-entry", targetUnitId = "method:demo-run")),
                        semanticUnits = buildList {
                            add(MethodLikeUnit("method:demo-run", "DemoService.run", codeHandle.methodSignature))
                            add(FlowActionUnit("action:trim", "value.trim()", "ACTION"))
                            if (expanded) {
                                add(FlowActionUnit("action:normalize", "normalize(value)", "ACTION"))
                            }
                        },
                        relations = buildList {
                            add(SemanticRelation(SemanticRelationKind.CONTAINS, "method:demo-run", "action:trim"))
                            if (expanded) {
                                add(SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "action:trim", "action:normalize"))
                            }
                        },
                        diagnostics = emptyList(),
                        boundaries = emptyList(),
                        sourceMappings = listOf(
                            SourceMapping(
                                sourcePath = "src/DemoService.java",
                                sourceRange = codeHandle.sourceRange,
                                targetUnitId = "method:demo-run",
                            ),
                        ),
                    )
                }),
            ),
        )
        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FACT_GRAPH)

        service.loadCurrentMethodGraphAsync()
        waitForSnapshot { snapshot ->
            snapshot.visibleGraph?.nodes?.any { node -> node.id == "action:trim" } == true
        }

        val stateService = project.getService(GraphEditorStateService::class.java)
        val loadedSnapshot = stateService.snapshot()
        val overflowNode = GraphNode(
            id = "overflow:downstream",
            type = NodeType.UNCERTAIN_LINK,
            title = "下游已折叠 1 个节点",
            metadata = mapOf(
                "linkGraph.overflow.direction" to "DOWNSTREAM",
                "linkGraph.overflow.titlePrefix" to "下游调用过多",
            ),
        )
        stateService.loadGraphProjection(
            visibleGraph = loadedSnapshot.visibleGraph!!.copy(nodes = loadedSnapshot.visibleGraph!!.nodes + overflowNode),
            fullGraph = loadedSnapshot.referenceFactGraph!!,
            source = loadedSnapshot.lastGraphSource ?: "currentMethod",
            selectedMethodSignature = loadedSnapshot.selectedMethodSignature,
        )

        service.requestExpandOverflowNode(overflowNode.id)
        waitForSnapshot { snapshot ->
            snapshot.visibleGraph?.nodes?.any { node -> node.id == "action:normalize" } == true
        }

        val expandedSnapshot = stateService.snapshot()
        assertEquals(2, analyzerCallCount.get())
        assertTrue(expandedSnapshot.visibleGraph?.nodes?.any { node -> node.id == "action:normalize" } == true)
        assertEquals("DemoService.run", expandedSnapshot.visibleGraph?.nodes?.firstOrNull { it.id == "method:demo-run" }?.title)
    }

    fun testDebugSignatureAutoloadRerunsSemanticAnalysisWithoutLegacyGraphExtractor() {
        myFixture.configureByText(
            "DemoService.java",
            """
                package com.example;

                class DemoService {
                    String run(String value) {
                        return <caret>value.trim();
                    }
                }
            """.trimIndent(),
        )
        val codeHandle = assertInstanceOf(
            CaretSubjectLocator().locate(project, myFixture.editor),
            CodeSubjectHandle::class.java,
        )
        val analyzerCallCount = AtomicInteger(0)
        val service = project.getService(LinkGraphProjectService::class.java)
        service.testSemanticAnalyzerOverride = SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(semanticProvider { handle, _ ->
                    analyzerCallCount.incrementAndGet()
                    SemanticAnalysisResult(
                        subject = handle,
                        anchors = listOf(SemanticAnchor(id = "anchor-entry", targetUnitId = "method:demo-run")),
                        semanticUnits = listOf(
                            MethodLikeUnit("method:demo-run", "DemoService.run", handle.methodSignature),
                            FlowActionUnit("action:trim", "value.trim()", "ACTION"),
                        ),
                        relations = listOf(
                            SemanticRelation(SemanticRelationKind.CONTAINS, "method:demo-run", "action:trim"),
                        ),
                        diagnostics = emptyList(),
                        boundaries = emptyList(),
                        sourceMappings = listOf(
                            SourceMapping(
                                sourcePath = "src/DemoService.java",
                                sourceRange = handle.sourceRange,
                                targetUnitId = "method:demo-run",
                            ),
                        ),
                    )
                }),
            ),
        )
        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FACT_GRAPH)

        val method = LinkGraphProjectService::class.java.getDeclaredMethod(
            "loadDebugMethodGraphBySignatureAsync",
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(service, codeHandle.methodSignature)

        waitForSnapshot { snapshot ->
            analyzerCallCount.get() == 1 &&
                snapshot.lastGraphSource == "currentMethod" &&
                snapshot.visibleGraph?.nodes?.any { node -> node.id == "action:trim" } == true
        }

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        assertEquals(1, analyzerCallCount.get())
        assertEquals("currentMethod", snapshot.lastGraphSource)
        assertTrue(snapshot.visibleGraph?.nodes?.any { node -> node.id == "method:demo-run" } == true)
    }

    private fun waitForSnapshot(
        predicate: (GraphEditorStateService.Snapshot) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (predicate(snapshot)) {
                return
            }
            Thread.sleep(50)
        }
        fail("等待统一语义分析结果超时")
    }

    private fun semanticProvider(
        analyze: (CodeSubjectHandle, TraversalBudgetPolicy) -> SemanticAnalysisResult,
    ): SemanticProvider {
        return object : SemanticProvider {
            override fun supports(handle: SubjectHandle): Boolean = handle is CodeSubjectHandle

            override fun analyze(
                handle: SubjectHandle,
                capturePolicy: SemanticCapturePolicy,
                budgetPolicy: TraversalBudgetPolicy,
            ): SemanticAnalysisResult {
                return analyze(handle as CodeSubjectHandle, budgetPolicy)
            }
        }
    }
}
