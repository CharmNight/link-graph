package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.command.ApplicationCommand
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectRuntimeHooks
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTarget
import com.charmnight.linkgraph.application.usecase.InvocationExpansionTargetKind
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.model.FlowActionUnit
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.model.SemanticAnchor
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.code.CodeSemanticProvider
import com.charmnight.linkgraph.semantic.provider.code.CodeSubjectSemanticProvider
import com.charmnight.linkgraph.semantic.provider.SemanticProvider
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.testing.*
import com.charmnight.linkgraph.ui.AsyncRequestPhase
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvocationExpansionWorkflowTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerGraphEditorApplicationServicesForTest()
    }

    fun testExpandsProjectSourceInvocationIntoCurrentWorkspaceGraph() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(callerGraph(), "test"))
        val overrides = project.getService(LinkGraphProjectRuntimeHooks::class.java)
        overrides.invocationExpansionTargetResolver = { _, signature ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.PROJECT_SOURCE, signature = signature)
        }
        overrides.invocationExpansionSubjectResolver = { signature -> testCodeSubject(signature) }
        overrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(targetProvider())),
        )

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:create-info"))
        val snapshot = waitForSnapshot {
            it.workspaceGraph.nodes.any { node -> node.id == "action:save-info" }
        }

        assertTrue(snapshot.workspaceGraph.nodes.any { node -> node.id == "method:create-info" })
        assertTrue(snapshot.workspaceGraph.nodes.any { node -> node.id == "action:save-info" })
        assertTrue(snapshot.workspaceGraph.edges.any { edge ->
            edge.fromNodeId == "invoke:create-info" && edge.toNodeId == "method:create-info"
        })
    }

    fun testRepeatedExpansionReopensExistingBatchWithoutSemanticAnalysisOrDuplicateCommit() {
        val service = project.graphEditorApplicationServiceForTest()
        val stateService = project.getService(GraphEditorStateService::class.java)
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(callerGraph(), "test"))
        val overrides = project.getService(LinkGraphProjectRuntimeHooks::class.java)
        var analysisCount = 0
        overrides.invocationExpansionTargetResolver = { _, signature ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.PROJECT_SOURCE, signature = signature)
        }
        overrides.invocationExpansionSubjectResolver = { signature -> testCodeSubject(signature) }
        overrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(
                    targetProvider { _, _ -> analysisCount += 1 },
                ),
            ),
        )

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:create-info"))
        val firstSnapshot = waitForSnapshot {
            it.workspaceGraph.nodes.any { node -> node.id == "action:save-info" }
        }
        val expansionId = requireNotNull(
            firstSnapshot.workspaceGraph.nodes
                .first { node -> node.id == "action:save-info" }
                .metadata["linkGraph.expansion.id"],
        )
        stateService.switchAnalysisDisplayMode(com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FLOWCHART)
        stateService.collapseInvocationExpansion(expansionId)
        assertTrue(stateService.snapshot().currentSceneState().invocationExpansionState.collapsedExpansionIds.contains(expansionId))

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:create-info"))
        val reopenedSnapshot = waitForSnapshot { snapshot ->
            analysisCount > 1 ||
                snapshot.operationFeedback?.message?.contains("已重新打开已有调用展开") == true
        }

        assertEquals(1, analysisCount)
        assertEquals(
            setOf(expansionId),
            (reopenedSnapshot.workspaceGraph.nodes.mapNotNull { node -> node.metadata["linkGraph.expansion.id"] } +
                reopenedSnapshot.workspaceGraph.edges.mapNotNull { edge -> edge.metadata["linkGraph.expansion.id"] }).toSet(),
        )
        assertEquals(
            1,
            reopenedSnapshot.workspaceGraph.edges.count { edge ->
                edge.type == EdgeType.CALL && edge.fromNodeId == "invoke:create-info"
            },
        )
        assertFalse(reopenedSnapshot.currentSceneState().invocationExpansionState.collapsedExpansionIds.contains(expansionId))
        assertEquals(expansionId, reopenedSnapshot.currentSceneState().invocationExpansionState.activeExpansionId)
    }

    fun testRightClickExpansionUsesLocalFlowBudgetWithoutUpstreamOrResourceTraversal() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(callerGraph(), "test"))
        val overrides = project.getService(LinkGraphProjectRuntimeHooks::class.java)
        var capturedCapturePolicy: SemanticCapturePolicy? = null
        var capturedBudgetPolicy: TraversalBudgetPolicy? = null
        overrides.invocationExpansionTargetResolver = { _, signature ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.PROJECT_SOURCE, signature = signature)
        }
        overrides.invocationExpansionSubjectResolver = { signature -> testCodeSubject(signature) }
        overrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(
                    targetProvider { capturePolicy, budgetPolicy ->
                        capturedCapturePolicy = capturePolicy
                        capturedBudgetPolicy = budgetPolicy
                    },
                ),
            ),
        )

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:create-info"))
        waitForSnapshot {
            it.workspaceGraph.nodes.any { node -> node.id == "action:save-info" }
        }

        val capturePolicy = requireNotNull(capturedCapturePolicy)
        val budgetPolicy = requireNotNull(capturedBudgetPolicy)
        assertEquals(0, budgetPolicy.maxDownstreamDepth)
        assertEquals(0, budgetPolicy.maxUpstreamDepth)
        assertEquals(0, budgetPolicy.maxRelatedResourcesPerUnit)
        assertFalse(capturePolicy.includeResourceReferences)
    }

    fun testRightClickExpansionDoesNotMaterializeAllAnalysisOutcomeViews() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(callerGraph(), "test"))
        val overrides = project.getService(LinkGraphProjectRuntimeHooks::class.java)
        val outcomeTraceMessages = mutableListOf<String>()
        overrides.invocationExpansionTargetResolver = { _, signature ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.PROJECT_SOURCE, signature = signature)
        }
        overrides.invocationExpansionSubjectResolver = { signature -> testCodeSubject(signature) }
        overrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(targetProvider())),
        )
        overrides.analysisOutcomeFactory = AnalysisOutcomeFactory(
            runtimeTrace = { message -> outcomeTraceMessages += message() },
        )

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:create-info"))
        waitForSnapshot {
            it.workspaceGraph.nodes.any { node -> node.id == "action:save-info" }
        }

        assertTrue(
            outcomeTraceMessages.none { message -> message.contains("analysis.outcome.") },
            "right-click expansion should not materialize full multi-view AnalysisOutcome, trace=$outcomeTraceMessages",
        )
    }

    fun testRightClickExpansionDoesNotMaterializeNestedCalleeBodies() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(callerGraph(), "test"))
        val overrides = project.getService(LinkGraphProjectRuntimeHooks::class.java)
        val handle = nestedCalleeSubject(CREATE_INFO_SIGNATURE)
        overrides.invocationExpansionTargetResolver = { _, signature ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.PROJECT_SOURCE, signature = signature)
        }
        overrides.invocationExpansionSubjectResolver = { handle }
        overrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(CodeSemanticProvider())),
        )

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:create-info"))
        val snapshot = waitForSnapshot {
            it.workspaceGraph.nodes.any { node -> node.title.contains("auditInfo()") }
        }

        val expandedTitles = snapshot.workspaceGraph.nodes
            .filter { node -> node.id != "method:caller" && node.id != "invoke:create-info" }
            .map(GraphNode::title)
        assertTrue(expandedTitles.any { title -> title.contains("saveInfo()") })
        assertTrue(expandedTitles.any { title -> title.contains("notifyInfo()") })
        assertTrue(expandedTitles.any { title -> title.contains("auditInfo()") })
        assertFalse(
            expandedTitles.any { title ->
                title.contains("deepSave()") ||
                    title.contains("deepNotify()") ||
                    title.contains("deepAudit()")
            },
            "right-click expansion should keep target method local; expandedTitles=$expandedTitles",
        )
    }

    fun testExplainsExpandedInvocationContentAfterExpansionWorkflow() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(callerGraph(), "test"))
        val overrides = project.getService(LinkGraphProjectRuntimeHooks::class.java)
        overrides.invocationExpansionTargetResolver = { _, signature ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.PROJECT_SOURCE, signature = signature)
        }
        overrides.invocationExpansionSubjectResolver = { signature -> testCodeSubject(signature) }
        overrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(targetProvider())),
        )

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:create-info"))
        val expandedSnapshot = waitForSnapshot {
            it.workspaceGraph.nodes.any { node -> node.id == "action:save-info" }
        }
        assertTrue(expandedSnapshot.workspaceGraph.nodes.any { node -> node.id == "action:save-info" })

        service.commandDispatcher.dispatch(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.EXPLAIN_CODE,
                actionId = AssistantActionId.EXPLAIN_FLOW,
                prompt = "解释展开后的调用链。请讲解新展开的被调方法内容",
                selectedNodeIds = listOf("method:create-info"),
            ),
        )
        val explainedSnapshot = waitForSnapshot {
            it.graphBeautificationRequestState.phase == AsyncRequestPhase.SUCCEEDED
        }
        val result = requireNotNull(explainedSnapshot.graphBeautificationResult)

        assertEquals(AsyncRequestPhase.SUCCEEDED, explainedSnapshot.graphBeautificationRequestState.phase)
        assertTrue(
            result.promptPreview.contains("SystemService.createInfo"),
            "prompt should include expanded target method, prompt=${result.promptPreview}",
        )
        assertTrue(
            result.promptPreview.contains("saveInfo()"),
            "prompt should include expanded target action, prompt=${result.promptPreview}",
        )
        assertTrue(
            result.promptPreview.contains("锚点节点：method:create-info"),
            "prompt should anchor the explicit expanded method, prompt=${result.promptPreview}",
        )
        assertTrue(result.steps.any { step ->
            step.primaryNodeId == "action:save-info" || step.description.contains("saveInfo()")
        })
    }

    fun testExplainsExpandedProjectedInvocationAliasInFlowchartMode() {
        val service = project.graphEditorApplicationServiceForTest()
        val stateService = project.getService(GraphEditorStateService::class.java)
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(projectedCallerGraph(), "test"))
        stateService.switchAnalysisDisplayMode(com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FLOWCHART)
        val overrides = project.getService(LinkGraphProjectRuntimeHooks::class.java)
        overrides.invocationExpansionTargetResolver = { _, signature ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.PROJECT_SOURCE, signature = signature)
        }
        overrides.invocationExpansionSubjectResolver = { signature -> testCodeSubject(signature) }
        overrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(targetProvider())),
        )

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("action:create-info"))
        val expandedSnapshot = waitForSnapshot {
            it.workspaceGraph.nodes.any { node -> node.id == "action:save-info" }
        }
        assertTrue(expandedSnapshot.workspaceGraph.nodes.any { node -> node.id == "action:save-info" })

        service.commandDispatcher.dispatch(
            ApplicationCommand.RequestAssistantTask(
                intent = AssistantIntent.EXPLAIN_CODE,
                actionId = AssistantActionId.EXPLAIN_FLOW,
                prompt = "解释展开后的调用链。请讲解新展开的被调方法内容",
                selectedNodeIds = listOf("method:create-info"),
            ),
        )
        val explainedSnapshot = waitForSnapshot {
            it.graphBeautificationRequestState.phase == AsyncRequestPhase.SUCCEEDED
        }
        val result = requireNotNull(explainedSnapshot.graphBeautificationResult)

        assertEquals(AsyncRequestPhase.SUCCEEDED, explainedSnapshot.graphBeautificationRequestState.phase)
        assertTrue(
            result.promptPreview.contains("SystemService.createInfo"),
            "prompt should include expanded target method, prompt=${result.promptPreview}",
        )
        assertTrue(
            result.promptPreview.contains("saveInfo()"),
            "prompt should include expanded target action, prompt=${result.promptPreview}",
        )
        assertTrue(
            result.promptPreview.contains("锚点节点：method:create-info"),
            "prompt should anchor the explicit expanded method, prompt=${result.promptPreview}",
        )
        assertTrue(result.steps.any { step ->
            step.primaryNodeId == "action:save-info" || step.description.contains("saveInfo()")
        })
    }

    fun testExpandsReadableProjectedInvocationAliasIntoCurrentWorkspaceGraph() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(ApplicationCommand.LoadGraph(projectedCallerGraph(), "test"))
        val overrides = project.getService(LinkGraphProjectRuntimeHooks::class.java)
        overrides.invocationExpansionTargetResolver = { _, signature ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.PROJECT_SOURCE, signature = signature)
        }
        overrides.invocationExpansionSubjectResolver = { signature -> testCodeSubject(signature) }
        overrides.semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(targetProvider())),
        )

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("action:create-info"))
        val snapshot = waitForSnapshot {
            it.workspaceGraph.nodes.any { node -> node.id == "action:save-info" }
        }

        assertTrue(snapshot.workspaceGraph.nodes.any { node -> node.id == "method:create-info" })
        assertTrue(snapshot.workspaceGraph.nodes.any { node -> node.id == "action:save-info" })
        assertTrue(snapshot.workspaceGraph.edges.any { edge ->
            edge.fromNodeId == "invoke:create-info" && edge.toNodeId == "method:create-info"
        })
    }

    fun testDoesNotExpandExternalJdkInvocation() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(
            ApplicationCommand.LoadGraph(
                GraphDocument(
                    nodes = listOf(
                        invocationNode(
                            id = "invoke:trim",
                            title = "value.trim()",
                            signature = "java.lang.String.trim():java.lang.String",
                        ),
                    ),
                ),
                "test",
            ),
        )
        project.getService(LinkGraphProjectRuntimeHooks::class.java).invocationExpansionTargetResolver = { _, _ ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.EXTERNAL_JDK)
        }

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:trim"))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse(project.getService(GraphEditorStateService::class.java).snapshot().workspaceGraph.nodes.any { it.id == "action:save-info" })
    }

    fun testExpandsMultipleInterfaceImplementationsIntoCurrentWorkspaceGraph() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(
            ApplicationCommand.LoadGraph(
                GraphDocument(
                    nodes = listOf(
                        invocationNode(
                            id = "invoke:service",
                            title = "service.createInfo()",
                            signature = "com.example.InfoService.createInfo():void",
                        ),
                    ),
                ),
                "test",
            ),
        )
        val targetSignatures = listOf(
            "com.example.AInfoService.createInfo():void",
            "com.example.BInfoService.createInfo():void",
        )
        project.getService(LinkGraphProjectRuntimeHooks::class.java).invocationExpansionTargetResolver = { _, _ ->
            InvocationExpansionTarget(
                kind = InvocationExpansionTargetKind.MULTIPLE_IMPLEMENTATIONS,
                candidateSignatures = targetSignatures,
            )
        }
        project.getService(LinkGraphProjectRuntimeHooks::class.java).invocationExpansionSubjectResolver = { signature -> testCodeSubject(signature) }
        project.getService(LinkGraphProjectRuntimeHooks::class.java).semanticAnalyzer = SemanticAnalyzer(
            registry = SemanticProviderRegistry(listOf(polymorphicTargetProvider(targetSignatures))),
        )

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:service"))
        val snapshot = waitForSnapshot {
            it.workspaceGraph.nodes.any { node -> node.id == "method:a-info-service-create-info" } &&
                it.workspaceGraph.nodes.any { node -> node.id == "method:b-info-service-create-info" }
        }

        assertTrue(snapshot.workspaceGraph.nodes.any { node -> node.id == "method:a-info-service-create-info" })
        assertTrue(snapshot.workspaceGraph.nodes.any { node -> node.id == "method:b-info-service-create-info" })
        assertTrue(snapshot.workspaceGraph.edges.any { edge ->
            edge.fromNodeId == "invoke:service" && edge.toNodeId == "method:a-info-service-create-info"
        })
        assertTrue(snapshot.workspaceGraph.edges.any { edge ->
            edge.fromNodeId == "invoke:service" && edge.toNodeId == "method:b-info-service-create-info"
        })
    }

    fun testDoesNotExpandThirdPartyLibraryInvocation() {
        val service = project.graphEditorApplicationServiceForTest()
        service.commandDispatcher.dispatch(
            ApplicationCommand.LoadGraph(
                GraphDocument(
                    nodes = listOf(
                        invocationNode(
                            id = "invoke:json",
                            title = "objectMapper.writeValueAsString(value)",
                            signature = "com.fasterxml.jackson.databind.ObjectMapper.writeValueAsString(java.lang.Object):java.lang.String",
                        ),
                    ),
                ),
                "test",
            ),
        )
        project.getService(LinkGraphProjectRuntimeHooks::class.java).invocationExpansionTargetResolver = { _, _ ->
            InvocationExpansionTarget(InvocationExpansionTargetKind.EXTERNAL_LIBRARY)
        }

        service.commandDispatcher.dispatch(ApplicationCommand.RequestExpandInvocation("invoke:json"))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse(project.getService(GraphEditorStateService::class.java).snapshot().workspaceGraph.nodes.any { it.id == "action:save-info" })
    }

    fun testRemovesExpansionBatch() {
        testExpandsProjectSourceInvocationIntoCurrentWorkspaceGraph()
        val stateService = project.getService(GraphEditorStateService::class.java)
        val expansionId = stateService.snapshot().workspaceGraph.nodes
            .first { it.id == "action:save-info" }
            .metadata["linkGraph.expansion.id"]!!

        project.graphEditorApplicationServiceForTest()
            .commandDispatcher
            .dispatch(ApplicationCommand.RequestRemoveInvocationExpansion(expansionId))
        val snapshot = waitForSnapshot {
            it.workspaceGraph.nodes.none { node -> node.id == "action:save-info" }
        }

        assertFalse(snapshot.workspaceGraph.nodes.any { node -> node.id == "action:save-info" })
    }

    private fun callerGraph(): GraphDocument =
        GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:caller",
                    type = NodeType.METHOD,
                    title = "Caller.run",
                    signature = "com.example.Caller.run():void",
                ),
                invocationNode(
                    id = "invoke:create-info",
                    title = "systemService.createInfo()",
                    signature = CREATE_INFO_SIGNATURE,
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "control:caller-to-invoke",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:caller",
                    toNodeId = "invoke:create-info",
                ),
            ),
        )

    private fun projectedCallerGraph(): GraphDocument =
        GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:caller",
                    type = NodeType.METHOD,
                    title = "Caller.run",
                    signature = "com.example.Caller.run():void",
                    metadata = mapOf("flowchart.kind" to "ENTRY"),
                ),
                GraphNode(
                    id = "action:create-info",
                    type = NodeType.FLOW_ACTION,
                    title = "systemService.createInfo()",
                    metadata = mapOf(
                        "flow.kind" to "ACTION",
                        "flowchart.kind" to "PROCESS",
                    ),
                ),
                invocationNode(
                    id = "invoke:create-info",
                    title = "调用 SystemService.createInfo",
                    signature = CREATE_INFO_SIGNATURE,
                ).copy(
                    metadata = mapOf(
                        "flow.kind" to "INVOCATION",
                        "flowchart.kind" to "SUBROUTINE",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "control:caller-to-action",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:caller",
                    toNodeId = "action:create-info",
                ),
                GraphEdge(
                    id = "control:action-to-invoke",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "action:create-info",
                    toNodeId = "invoke:create-info",
                ),
            ),
        )

    private fun invocationNode(
        id: String,
        title: String,
        signature: String,
    ): GraphNode =
        GraphNode(
            id = id,
            type = NodeType.FLOW_ACTION,
            title = title,
            signature = signature,
            metadata = mapOf("flow.kind" to "INVOCATION"),
        )

    private fun testCodeSubject(signature: String): CodeSubjectHandle {
        myFixture.configureByText(
            "SystemService.java",
            """
                package com.example;
                class SystemService {
                    void createInfo() {
                        <caret>saveInfo();
                    }
                    void saveInfo() {}
                }
            """.trimIndent(),
        )
        val handle = CaretSubjectLocator().locate(project, myFixture.editor) as CodeSubjectHandle
        return handle.copy(methodSignature = signature)
    }

    private fun nestedCalleeSubject(signature: String): CodeSubjectHandle {
        myFixture.configureByText(
            "SystemService.java",
            """
                package com.example;
                class SystemService {
                    void createInfo() {
                        <caret>saveInfo();
                        notifyInfo();
                        auditInfo();
                    }
                    void saveInfo() { deepSave(); }
                    void notifyInfo() { deepNotify(); }
                    void auditInfo() { deepAudit(); }
                    void deepSave() {}
                    void deepNotify() {}
                    void deepAudit() {}
                }
            """.trimIndent(),
        )
        val handle = CaretSubjectLocator().locate(project, myFixture.editor) as CodeSubjectHandle
        return handle.copy(methodSignature = signature)
    }

    private fun targetProvider(
        onAnalyze: (SemanticCapturePolicy, TraversalBudgetPolicy) -> Unit = { _, _ -> },
    ): SemanticProvider =
        object : CodeSubjectSemanticProvider {
            override val supportedKinds: Set<CodeSubjectKind> = setOf(CodeSubjectKind.JAVA_METHOD)

            override fun analyze(
                handle: SubjectHandle,
                capturePolicy: SemanticCapturePolicy,
                budgetPolicy: TraversalBudgetPolicy,
            ): SemanticAnalysisResult {
                onAnalyze(capturePolicy, budgetPolicy)
                val codeHandle = handle as CodeSubjectHandle
                return SemanticAnalysisResult(
                    subject = codeHandle,
                    anchors = listOf(SemanticAnchor(id = "anchor:create", targetUnitId = "method:create-info")),
                    semanticUnits = listOf(
                        MethodLikeUnit(
                            id = "method:create-info",
                            title = "SystemService.createInfo",
                            signature = CREATE_INFO_SIGNATURE,
                        ),
                        FlowActionUnit(
                            id = "action:save-info",
                            title = "saveInfo()",
                            actionKind = "ACTION",
                        ),
                    ),
                    relations = listOf(
                        SemanticRelation(SemanticRelationKind.CONTROL_FLOW, "method:create-info", "action:save-info"),
                    ),
                    diagnostics = emptyList(),
                    boundaries = emptyList(),
                    sourceMappings = emptyList(),
                )
            }
        }

    private fun polymorphicTargetProvider(targetSignatures: List<String>): SemanticProvider =
        object : CodeSubjectSemanticProvider {
            override val supportedKinds: Set<CodeSubjectKind> = setOf(CodeSubjectKind.JAVA_METHOD)

            override fun analyze(
                handle: SubjectHandle,
                capturePolicy: SemanticCapturePolicy,
                budgetPolicy: TraversalBudgetPolicy,
            ): SemanticAnalysisResult {
                val codeHandle = handle as CodeSubjectHandle
                val targetIndex = targetSignatures.indexOf(codeHandle.methodSignature).takeIf { it >= 0 } ?: 0
                val owner = if (targetIndex == 0) "AInfoService" else "BInfoService"
                val methodId = if (targetIndex == 0) "method:a-info-service-create-info" else "method:b-info-service-create-info"
                val actionId = if (targetIndex == 0) "action:a-info-service-save-info" else "action:b-info-service-save-info"
                return SemanticAnalysisResult(
                    subject = codeHandle,
                    anchors = listOf(SemanticAnchor(id = "anchor:$methodId", targetUnitId = methodId)),
                    semanticUnits = listOf(
                        MethodLikeUnit(
                            id = methodId,
                            title = "$owner.createInfo",
                            signature = codeHandle.methodSignature,
                        ),
                        FlowActionUnit(
                            id = actionId,
                            title = "$owner.saveInfo()",
                            actionKind = "ACTION",
                        ),
                    ),
                    relations = listOf(
                        SemanticRelation(SemanticRelationKind.CONTROL_FLOW, methodId, actionId),
                    ),
                    diagnostics = emptyList(),
                    boundaries = emptyList(),
                    sourceMappings = emptyList(),
                )
            }
        }

    private fun waitForSnapshot(
        predicate: (com.charmnight.linkgraph.ui.GraphEditorStateSnapshot) -> Boolean,
    ): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot {
        val deadline = System.currentTimeMillis() + 15_000
        var latest = project.getService(GraphEditorStateService::class.java).snapshot()
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            latest = project.getService(GraphEditorStateService::class.java).snapshot()
            if (predicate(latest)) {
                return latest
            }
            Thread.sleep(50)
        }
        throw AssertionError(
            "等待调用展开结果超时，latestNodes=${latest.workspaceGraph.nodes.map { it.id }}，" +
                "latestFeedback=${latest.operationFeedback?.message}",
        )
    }

    private companion object {
        const val CREATE_INFO_SIGNATURE = "com.example.SystemService.createInfo():void"
    }
}
