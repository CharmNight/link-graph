package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.testing.*

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
import com.charmnight.linkgraph.semantic.model.FlowScopeCategory
import com.charmnight.linkgraph.semantic.model.FlowScopeUnit
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
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SourceRange
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphProjectServiceSemanticAnalysisTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerServiceInstance(GraphEditorStateService::class.java, GraphEditorStateService())
        project.registerServiceInstance(LinkGraphProjectTestOverrides::class.java, LinkGraphProjectTestOverrides())
        project.registerServiceInstance(LinkGraphProjectService::class.java, LinkGraphProjectService(project))
    }

    fun testLoadCurrentEditorContextGraphKeepsResourceSubjectsInResourceRelationScene() {
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                当前链路入口
            """.trimIndent(),
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        val resourceHandle = ResourceSubjectHandle(
            subjectId = "resource-markdown:order-flow-md",
            sourcePath = "order-flow.md",
            sourceRange = SourceRange(startOffset = 0, endOffset = 10, startLine = 1, endLine = 1),
            displayName = "order-flow.md",
            kind = ResourceSubjectKind.MARKDOWN_PAGE,
            attributes = mapOf("path" to "order-flow.md"),
        )
        testOverrides.subjectLocator = object : SubjectLocator {
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
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
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
        service.loadCurrentEditorContextGraphAsync(myFixture.editor)
        waitForSnapshot { snapshot ->
            snapshot.lastGraphSource == "currentContext" &&
                snapshot.analysisDisplayMode == AnalysisDisplayMode.RESOURCE_RELATION_VIEW &&
                snapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.DOC_PAGE && node.title == "order-flow.md" } == true &&
                snapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.METHOD && node.title == "OrderService.submit" } == true
        }

        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)
        waitForSnapshot { snapshot ->
            snapshot.analysisDisplayMode == AnalysisDisplayMode.RESOURCE_RELATION_VIEW &&
                snapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.DOC_PAGE && node.title == "order-flow.md" } == true &&
                snapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.METHOD && node.title == "OrderService.submit" } == true
        }
    }

    fun testLoadCurrentMethodGraphDefaultsToFlowchartDisplayMode() {
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
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        val codeHandle = assertInstanceOf(
            CaretSubjectLocator().locate(project, myFixture.editor),
            CodeSubjectHandle::class.java,
        )
        testOverrides.subjectLocator = object : SubjectLocator {
            override fun locate(
                project: Project,
                editor: Editor?,
                commitDocument: Boolean,
            ): SubjectHandle = codeHandle

            override fun previewKind(
                project: Project,
                editor: Editor?,
                commitDocument: Boolean,
            ): SubjectPreviewKind = SubjectPreviewKind.CODE_SUBJECT
        }
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(provider)),
        )

        service.loadCurrentEditorContextGraphAsync(myFixture.editor)
        waitForSnapshot { snapshot ->
            snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART &&
                snapshot.visibleGraph?.nodes?.any { node -> node.id == "action:trim" } == true
        }
    }

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
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(provider)),
        )
        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FACT_GRAPH)

        service.loadCurrentEditorContextGraphAsync(myFixture.editor)
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
                snapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.METHOD } == true &&
                snapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.DOC_PAGE } == true &&
                snapshot.visibleGraph?.nodes?.none { node -> node.type == NodeType.FLOW_ACTION } == true
        }

        val resourceSnapshot = stateService.snapshot()
        assertEquals(1, analyzerCallCount.get())
        assertTrue(resourceSnapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.METHOD } == true)
        assertTrue(resourceSnapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.DOC_PAGE } == true)
        assertFalse(resourceSnapshot.visibleGraph?.nodes?.any { node -> node.type == NodeType.FLOW_ACTION } == true)
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
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(provider)),
        )
        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)

        service.loadCurrentEditorContextGraphAsync(myFixture.editor)
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
                snapshot.visibleGraph?.nodes?.any { node -> node.id == "design:manual-step" } == true
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
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(provider)),
        )
        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FACT_GRAPH)

        service.loadCurrentEditorContextGraphAsync(myFixture.editor)
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

    fun testFlowchartDisplayModeKeepsIncompleteSummaryWhenProjectionIsTruncated() {
        myFixture.configureByText(
            "DemoService.java",
            """
                package com.example;

                class DemoService {
                    void <caret>run(String value) {
                        step0(value);
                    }

                    void step0(String value) {}
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
                val method = MethodLikeUnit(
                    id = "method:demo-run",
                    title = "DemoService.run",
                    signature = codeHandle.methodSignature,
                )
                val loop = FlowScopeUnit(
                    id = "scope:loop",
                    title = "while (true)",
                    scopeKind = "WHILE",
                    scopeCategory = FlowScopeCategory.LOOP_PRE_TEST,
                    incomplete = true,
                )
                val actions = (0 until 30).map { index ->
                    FlowActionUnit(
                        id = "action:$index",
                        title = "step$index(value)",
                        actionKind = "ACTION",
                    )
                }
                val relations = buildList {
                    add(SemanticRelation(SemanticRelationKind.CONTROL_FLOW, method.id, loop.id))
                    add(SemanticRelation(SemanticRelationKind.CONTROL_FLOW, loop.id, actions.first().id))
                    actions.zipWithNext().forEach { (from, to) ->
                        add(SemanticRelation(SemanticRelationKind.CONTROL_FLOW, from.id, to.id))
                    }
                }
                return SemanticAnalysisResult(
                    subject = codeHandle,
                    anchors = listOf(
                        SemanticAnchor(
                            id = "anchor-entry",
                            targetUnitId = method.id,
                            label = "入口",
                        ),
                    ),
                    semanticUnits = listOf(method, loop) + actions,
                    relations = relations,
                    diagnostics = emptyList(),
                    boundaries = emptyList(),
                    sourceMappings = listOf(
                        SourceMapping(
                            sourcePath = "src/DemoService.java",
                            sourceRange = codeHandle.sourceRange,
                            targetUnitId = method.id,
                        ),
                    ),
                )
            }
        }

        val service = project.getService(LinkGraphProjectService::class.java)
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(provider)),
        )
        service.requestAnalysisDisplayMode(AnalysisDisplayMode.FLOWCHART)

        service.loadCurrentEditorContextGraphAsync(myFixture.editor)
        waitForSnapshot { snapshot ->
            snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART &&
                snapshot.flowchartView?.summary?.semanticallyIncomplete == true
        }

        val flowchartView = project.getService(GraphEditorStateService::class.java).snapshot().flowchartView
        assertTrue(flowchartView != null)
        val summary = flowchartView!!.summary
        assertEquals(
            flowchartView.fullGraph.nodes.size - flowchartView.visibleGraph.nodes.size,
            summary.hiddenNodeCount,
        )
        assertEquals(
            flowchartView.fullGraph.edges.size - flowchartView.visibleGraph.edges.size,
            summary.hiddenEdgeCount,
        )
        assertEquals(summary.hiddenNodeCount > 0 || summary.hiddenEdgeCount > 0, summary.truncated)
        assertEquals(1, summary.incompleteNodeCount)
        assertTrue(summary.semanticallyIncomplete)
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
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.subjectLocator = object : com.charmnight.linkgraph.semantic.subject.SubjectLocator {
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

    fun testRequestExpandOverflowNodeRerunsSemanticAnalysisWithSemanticPipelineOnly() {
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
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
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

        service.loadCurrentEditorContextGraphAsync(myFixture.editor)
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

    fun testDebugSignatureAutoloadRerunsSemanticAnalysisWithSemanticPipelineOnly() {
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
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        testOverrides.semanticAnalyzer = SemanticAnalyzer(
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

    fun testLoadCurrentEditorContextGraphKeepsExternalInvocationForKotlinAccessor() {
        myFixture.configureByText(
            "AccessorService.kt",
            """
                package com.example

                class Formatter {
                    fun normalize(value: String): String {
                        return value.trim()
                    }
                }

                class AccessorService(
                    private val formatter: Formatter = Formatter(),
                ) {
                    var raw: String = " seed "
                        get() = formatter.normalize(<caret>field)
                }
            """.trimIndent(),
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        service.loadCurrentEditorContextGraphAsync(myFixture.editor)

        waitForSnapshot { snapshot ->
            snapshot.lastGraphSource == "currentMethod" &&
                snapshot.visibleGraph?.nodes?.any { node -> node.title.contains("Formatter.normalize") } == true
        }
    }

    fun testLoadCurrentEditorContextGraphKeepsExternalInvocationForKotlinPrimaryConstructor() {
        myFixture.configureByText(
            "PrimaryCtorFlow.kt",
            """
                package com.example

                class Formatter {
                    fun normalize(value: String): String {
                        return value.trim()
                    }
                }

                class PrimaryCtorFlow(
                    value: String,
                    private val formatter: Formatter = Formatter(),
                ) {
                    private val normalized = formatter.normalize(<caret>value)
                }
            """.trimIndent(),
        )

        val service = project.getService(LinkGraphProjectService::class.java)
        val testOverrides = project.getService(LinkGraphProjectTestOverrides::class.java)
        service.loadCurrentEditorContextGraphAsync(myFixture.editor)

        waitForSnapshot { snapshot ->
            snapshot.lastGraphSource == "currentMethod" &&
                snapshot.visibleGraph?.nodes?.any { node -> node.title.contains("Formatter.normalize") } == true
        }
    }

    private fun waitForSnapshot(
        predicate: (com.charmnight.linkgraph.ui.GraphEditorStateSnapshot) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 15_000
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
